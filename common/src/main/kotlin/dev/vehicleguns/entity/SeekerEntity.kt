package dev.vehicleguns.entity

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.SeekerSpec
import dev.vehicleguns.api.TargetRules
import dev.vehicleguns.ballistics.VehicleFrame
import dev.vehicleguns.gun.Aiming
import dev.vehicleguns.network.GunHitPayload
import dev.vehicleguns.network.Net
import dev.vehicleguns.network.TorpedoBurstPayload
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A homing round ([SeekerSpec]): a torpedo, which runs under water and seeks what is in it, or a missile, which flies
 * (slowly until it is clear of any water it starts in) and seeks what is not wholly under water. It turns onto its mark
 * a little each tick and bursts on it, on the first thing it runs into, or at the end of its run. A gunner's round
 * hurts anything alive but the gunner and those riding with them; a guard's only hostile mobs. It breaks no block.
 */
class SeekerEntity(type: EntityType<out SeekerEntity>, level: Level) : Projectile(type, level) {
    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(KIND, 0)
    }

    var kind: SeekerSpec.Kind
        get() = SeekerSpec.Kind.entries.getOrElse(entityData.get(KIND)) { SeekerSpec.Kind.TORPEDO }
        set(value) = entityData.set(KIND, value.ordinal)

    val torpedo: Boolean get() = kind == SeekerSpec.Kind.TORPEDO

    // Server only, set as it is launched ([arm]).
    var targetId = -1
    private var guard = true
    private var rules: TargetRules = TargetRules.DEFAULT
    private var vehicleId = -1
    private var speed = 1.4
    private var turn = 8.0
    private var range = 40.0
    private var damage = 10f
    private var splash = 5f
    private var radius = 2.5
    private var life = 60

    private var hits = 0
    private var kills = 0
    /** Client: whether its run has been told to the client's effects. */
    private var started = false

    /** Loads the round as [spec] says, after [target] (an entity id, -1 for none), launched from [vehicle]. */
    fun arm(spec: SeekerSpec, rules: TargetRules, guard: Boolean, vehicle: Entity, target: Int) {
        kind = spec.kind
        this.rules = rules
        this.guard = guard
        vehicleId = vehicle.id
        targetId = target
        speed = spec.speed
        turn = spec.turn
        range = spec.range
        damage = spec.damage
        splash = spec.splash
        radius = spec.radius
        life = spec.lifeTicks
    }

    override fun shouldBeSaved(): Boolean = false

    /** Whether it may hurt [entity]. A guard's hurts the mob it was sent after as well as any hostile one. */
    fun mayHurt(entity: Entity): Boolean {
        if (entity.id == vehicleId || entity.rootVehicle.id == vehicleId || entity is SeekerEntity || !entity.isAlive) return false
        if (guard) return rules.guardMayHit(entity) || (entity.id == targetId && entity is LivingEntity && entity !is Player)
        return rules.gunnerMayHit(entity) && !aboard(entity)
    }

    /** The gunner who launched it, or anyone riding with them. */
    private fun aboard(target: Entity): Boolean {
        val gunner = owner ?: return false
        return target === gunner || (gunner.rootVehicle !== gunner && target.rootVehicle === gunner.rootVehicle)
    }

    /** Whether it may seek [entity]: in the water for a torpedo, not wholly under it for a missile. */
    fun seeks(entity: Entity): Boolean = mayHurt(entity) && if (torpedo) entity.isInWater else !submerged(level(), entity)

    override fun canHitEntity(target: Entity): Boolean = target.isPickable && !target.isSpectator && mayHurt(target)

    override fun tick() {
        super.tick()
        val motion = deltaMovement
        if (level().isClientSide) {
            if (!started) { started = true; clientStarted(this) }
            trail()
            setPos(x + motion.x, y + motion.y, z + motion.z)
            face(motion)
            return
        }
        val level = level() as ServerLevel
        if (tickCount > life) { burst(level, position(), null); return }
        steer(level)
        val from = position()
        val hit = ProjectileUtil.getHitResultOnMoveVector(this) { canHitEntity(it) }
        if (hit.type != HitResult.Type.MISS) { burst(level, hit.location, (hit as? EntityHitResult)?.entity); return }
        // A missile coming down into water bursts on its face: what is under it is out of its reach.
        if (!torpedo && !isInWater && tickCount > 4) {
            val met = level.clip(ClipContext(from, from.add(deltaMovement), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this))
            if (met.type == HitResult.Type.BLOCK && !level.getFluidState(met.blockPos).isEmpty) { burst(level, met.location, null); return }
        }
        // Close by its mark it goes off: a round seldom runs into a swimmer dead on.
        val mark = level.getEntity(targetId)
        if (mark != null && mark.boundingBox.inflate(PROXIMITY).contains(from)) { burst(level, from, mark); return }
        setPos(from.add(deltaMovement))
        face(deltaMovement)
    }

    /** Its heading and pitch along [motion], as it is drawn. */
    private fun face(motion: Vec3) {
        if (motion.lengthSqr() < 1.0e-6) return
        yRot = Math.toDegrees(Math.atan2(-motion.x, motion.z)).toFloat()
        xRot = Math.toDegrees(Math.atan2(-motion.y, Math.hypot(motion.x, motion.z))).toFloat()
        if (tickCount <= 1) { yRotO = yRot; xRotO = xRot }
    }

    /**
     * Its way this tick: turned onto its mark (a new one near where it heads if that one is gone), gathering way up to
     * its speed; a torpedo kept under the water's face and never climbing or diving steeply, a missile slowed in water.
     */
    private fun steer(level: ServerLevel) {
        var mark = level.getEntity(targetId)?.takeIf { seeks(it) && it.distanceToSqr(this) <= range * range }
        if (mark == null && tickCount % REACQUIRE_TICKS == 0) {
            val ahead = deltaMovement.normalize()
            mark = level.getEntitiesOfClass(Mob::class.java, boundingBox.inflate(range * REACQUIRE_REACH)) { rules.guardMayHit(it) && seeks(it) }
                .filter { Aiming.aimPoint(it).subtract(position()).normalize().dot(ahead) > REACQUIRE_COS }
                .minByOrNull { it.distanceToSqr(this) }
        }
        targetId = mark?.id ?: -1
        val wet = isInWater
        val top = if (!torpedo && wet) speed * UNDERWATER_SHARE else speed
        val pace = Math.min(top, deltaMovement.length() + top * GATHER)
        var way = deltaMovement.normalize()
        if (mark != null) way = VehicleFrame.turnToward(way, Aiming.aimPoint(mark).subtract(position()).normalize(), turn)
        if (torpedo) {
            val flat = Math.hypot(way.x, way.z)
            val most = Math.tan(Math.toRadians(MOST_PITCH)) * flat
            if (flat > 1.0e-4 && Math.abs(way.y) > most) way = Vec3(way.x, Math.signum(way.y) * most, way.z).normalize()
            // Under the water's face it stays: up against it, it runs level; out of the water, it falls back in.
            val above = BlockPos.containing(position().add(way.scale(pace)).add(0.0, 0.3, 0.0))
            if (!wet) way = Vec3(way.x, Math.min(way.y, 0.0) - 0.15, way.z)
            else if (!level.getFluidState(above).`is`(FluidTags.WATER) && way.y > 0.0) way = Vec3(way.x, 0.0, way.z)
            if (way.lengthSqr() > 1.0e-6) way = way.normalize()
        }
        deltaMovement = way.scale(pace)
    }

    /** Client: its wake. A torpedo's is a stream of fine bubbles and, near the face, a track on it; a missile's smoke and flame. */
    private fun trail() {
        val back = position().subtract(deltaMovement)
        val level = level()
        if (isInWater) {
            waterFace(level, position(), TRACK_DEPTH)?.let { clientTrack(this, back, it) }
            val way = position().subtract(back)
            val tail = way.takeIf { it.lengthSqr() > 1.0e-6 }?.normalize()?.scale(-TAIL) ?: Vec3.ZERO
            val r = level.random
            for (k in 0 until WAKE_BUBBLES) {
                val at = back.add(way.scale(k / WAKE_BUBBLES.toDouble())).add(tail)
                level.addParticle(ParticleTypes.BUBBLE, at.x + (r.nextDouble() - 0.5) * 0.12, at.y + (r.nextDouble() - 0.5) * 0.12, at.z + (r.nextDouble() - 0.5) * 0.12, 0.0, 0.02, 0.0)
            }
            val at = back.add(tail)
            level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP, at.x, at.y, at.z, 0.0, 0.05, 0.0)
        } else {
            level.addParticle(ParticleTypes.SMOKE, true, back.x, back.y, back.z, 0.0, 0.01, 0.0)
            level.addParticle(ParticleTypes.SMALL_FLAME, back.x, back.y, back.z, 0.0, 0.0, 0.0)
            if (tickCount % 3 == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, true, back.x, back.y, back.z, 0.0, 0.01, 0.0)
        }
    }

    override fun onHitEntity(result: EntityHitResult) {}

    /**
     * It goes off at [at]: [struck] takes its whole blow, everything else it may hurt within its radius its splash,
     * less the further off, and is thrown back a little. Nothing is broken. Its gunner is told what it hurt.
     */
    private fun burst(level: ServerLevel, at: Vec3, struck: Entity?) {
        val source = GunDamage.source(level, VehicleGuns.SEEKER_DAMAGE, this, owner)
        fun blow(target: LivingEntity, amount: Float) {
            val health = target.health
            target.invulnerableTime = 0
            if (!target.hurt(source, amount)) return
            if (target.health < health) hits++
            if (target.isDeadOrDying) kills++
        }
        val first = (struck as? LivingEntity)?.takeIf { mayHurt(it) }
        if (first != null) blow(first, damage)
        for (other in level.getEntitiesOfClass(LivingEntity::class.java, AABB(at, at).inflate(radius)) { it !== first && mayHurt(it) }) {
            val near = 1.0 - other.boundingBox.center.distanceTo(at) / radius
            if (near <= 0.0) continue
            blow(other, (splash * near).toFloat())
            val push = other.boundingBox.center.subtract(at).normalize().scale(0.4 * near)
            other.push(push.x, push.y + 0.1 * near, push.z)
        }
        if (!level.getFluidState(BlockPos.containing(at)).isEmpty) {
            // Under water every client near stages it (TorpedoFx): a flash and shock, bubbles, a thump, a column over it.
            val payload = TorpedoBurstPayload(at.x, at.y, at.z, waterFace(level, at, BURST_FACE_DEPTH) ?: Double.NaN, bedNear(level, at))
            for (player in level.players()) if (player.distanceToSqr(at) < BURST_SEEN * BURST_SEEN) Net.toPlayer(player, payload)
        } else {
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, 0.3, 0.3, 0.3, 0.0)
            level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 16, 0.4, 0.4, 0.4, 0.08)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 10, 0.5, 0.5, 0.5, 0.03)
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 1.6f, 1.1f + random.nextFloat() * 0.15f)
        }
        (owner as? ServerPlayer)?.let { Net.toPlayer(it, GunHitPayload(at.x, at.y, at.z, hits, kills)) }
        discard()
    }

    companion object {
        private val KIND: EntityDataAccessor<Int> = SynchedEntityData.defineId(SeekerEntity::class.java, EntityDataSerializers.INT)

        /** A torpedo climbs or dives no steeper than this, degrees. */
        const val MOST_PITCH = 22.0
        /** A missile goes this share of its speed until it is out of the water. */
        const val UNDERWATER_SHARE = 0.45
        /** It gathers this share of its top speed a tick out of its launcher. */
        private const val GATHER = 0.12
        /** Within this of its mark's box, blocks, it goes off. */
        private const val PROXIMITY = 0.6
        /** Its mark lost, it looks every few ticks for another within this share of its range, no more than about 50 degrees off its heading. */
        private const val REACQUIRE_TICKS = 4
        private const val REACQUIRE_REACH = 0.6
        private val REACQUIRE_COS = Math.cos(Math.toRadians(50.0))
        /** A torpedo's tail, blocks behind its middle, and the bubbles of its wake each tick. */
        private const val TAIL = 0.6
        private const val WAKE_BUBBLES = 4
        /** A torpedo this near the face leaves a track on it; bursting this near, throws a column up out of it; seen this far. */
        private const val TRACK_DEPTH = 3.0
        private const val BURST_FACE_DEPTH = 7.0
        private const val BURST_SEEN = 96.0

        /** Client: a round seen for the first time (its run's sound); set by the client's setup. */
        @JvmStatic var clientStarted: (SeekerEntity) -> Unit = {}

        /** Client: a torpedo's track on the water's face at the given height, from where it was last tick; set by the client's setup. */
        @JvmStatic var clientTrack: (SeekerEntity, Vec3, Double) -> Unit = { _, _, _ -> }

        /** Whether [entity] is wholly under water: the top of it is. */
        fun submerged(level: Level, entity: Entity): Boolean =
            !level.getFluidState(BlockPos.containing(entity.x, entity.y + entity.bbHeight * 0.9, entity.z)).isEmpty

        /** The water's face over [at], if it is in water with open air over the water within [depth] blocks; else null. */
        fun waterFace(level: Level, at: Vec3, depth: Double): Double? {
            val pos = BlockPos.MutableBlockPos(Math.floor(at.x).toInt(), Math.floor(at.y).toInt(), Math.floor(at.z).toInt())
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) return null
            val top = at.y + depth
            while (pos.y <= top && level.getFluidState(pos).`is`(FluidTags.WATER)) pos.move(0, 1, 0)
            if (pos.y > top + 1 || !level.getBlockState(pos).isAir) return null
            return pos.y.toDouble()
        }

        /** The bed or bank nearest [at] within a block and a half (as Block.getId), what a burst there stirs up; -1 for none. */
        fun bedNear(level: Level, at: Vec3): Int {
            val centre = BlockPos.containing(at)
            var best: BlockState? = null
            var bestDistance = Double.MAX_VALUE
            for (dx in -1..1) for (dy in -2..1) for (dz in -1..1) {
                val pos = centre.offset(dx, dy, dz)
                val state = level.getBlockState(pos)
                if (state.getCollisionShape(level, pos).isEmpty) continue
                val distance = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5).distanceToSqr(at)
                if (distance < bestDistance && distance <= BED_REACH * BED_REACH) { best = state; bestDistance = distance }
            }
            return best?.let { Block.getId(it) } ?: -1
        }

        private const val BED_REACH = 2.25
    }
}
