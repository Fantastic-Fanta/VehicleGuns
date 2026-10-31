package dev.vehicleguns.entity

import dev.vehicleguns.GunSettings
import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.RoundSpec
import dev.vehicleguns.api.TargetRules
import dev.vehicleguns.network.GunHitPayload
import dev.vehicleguns.network.Net
import dev.vehicleguns.network.ShellBurstPayload
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.ExplosionDamageCalculator
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A gun's round in flight. It bursts on the first block, water surface or creature it may hit, as an explosion of its
 * round's blast power. A gunner's round hurts anything alive but the gunner and those riding with them, and breaks
 * blocks as [GunSettings.blastGriefing] says; a guard's ([guard]) meets, hurts and throws only hostile mobs and spares
 * every block. Its burst is drawn by every client near it ([ShellBurstPayload]), and its gunner is told what it hit.
 */
class ShellEntity(type: EntityType<out ShellEntity>, level: Level) : ThrowableItemProjectile(type, level) {
    // Set by the gun as it fires; a round is never saved, so they need not be either.
    private var guard = false
    private var rules: TargetRules = TargetRules.DEFAULT
    private var lifeTicks = 200
    private var blastPower = 4f
    private var fireSeconds = 5f

    private var hits = 0
    private var kills = 0
    private var onWater = false

    /** Loads the round as [round] says, sorting who it may hit by [rules]; a [guard]'s spares all but hostile mobs. */
    fun arm(round: RoundSpec, rules: TargetRules, guard: Boolean) {
        this.guard = guard
        this.rules = rules
        lifeTicks = round.lifeTicks
        blastPower = round.blastPower
        fireSeconds = round.fireSeconds
        entityData.set(GRAVITY, round.gravity.toFloat())
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(GRAVITY, 0.045f)
    }

    override fun getDefaultItem(): Item = Items.FIRE_CHARGE

    // The client moves it too, between the server's updates: with the same fall.
    override fun getDefaultGravity(): Double = entityData.get(GRAVITY).toDouble()

    /** A round caught by a chunk unloading is gone, not saved half-armed. */
    override fun shouldBeSaved(): Boolean = false

    private fun mayHit(target: Entity): Boolean = if (guard) rules.guardMayHit(target) else rules.gunnerMayHit(target) && !aboard(target)

    /** The gunner who fired it, or anyone riding with them. */
    private fun aboard(target: Entity): Boolean {
        val gunner = owner ?: return false
        return target === gunner || (gunner.rootVehicle !== gunner && target.rootVehicle === gunner.rootVehicle)
    }

    override fun canHitEntity(target: Entity): Boolean = super.canHitEntity(target) && mayHit(target)

    override fun tick() {
        // A projectile meets only blocks: one falling into the sea went on to burst unseen on the bottom.
        if (!level().isClientSide && !isInWater) {
            val from = position()
            val met = level().clip(ClipContext(from, from.add(deltaMovement), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this))
            if (met.type == HitResult.Type.BLOCK && !level().getFluidState(met.blockPos).isEmpty) {
                setPos(met.location)
                onWater = true
                onHit(met)
                return
            }
        }
        super.tick()
        if (level().isClientSide) {
            // A trail of smoke, thicker every few ticks, to follow it up and down its arc; none in front of the muzzle.
            if (tickCount >= SHOWN_AFTER) {
                level().addParticle(ParticleTypes.SMOKE, true, (x + xo) / 2, (y + yo) / 2, (z + zo) / 2, 0.0, 0.01, 0.0)
                if (tickCount % 4 == 0) level().addParticle(ParticleTypes.LARGE_SMOKE, true, xo, yo, zo, 0.0, 0.01, 0.0)
            }
        } else if (tickCount > lifeTicks) {
            discard()
        }
    }

    override fun onHit(result: HitResult) {
        super.onHit(result)
        val level = level() as? ServerLevel ?: return
        val at = result.location
        explode(level, at)
        val water = onWater || isInWater
        for (player in level.players()) {
            if (player.distanceToSqr(at) < SEEN_FROM * SEEN_FROM) Net.toPlayer(player, ShellBurstPayload(at.x, at.y, at.z, water))
        }
        (owner as? ServerPlayer)?.let { Net.toPlayer(it, GunHitPayload(at.x, at.y, at.z, hits, kills)) }
        discard()
    }

    /**
     * The burst, as the game's own explosion of [blastPower]: its blow falls off with distance and behind cover. It
     * spares whoever it may not hit, throws nobody a guard's round may not hurt, and sets alight what it hurts.
     */
    private fun explode(level: ServerLevel, at: Vec3) {
        val alive = level.getEntitiesOfClass(LivingEntity::class.java, AABB(at, at).inflate(blastPower * 2.0)) { it.isAlive && mayHit(it) }
            .associateWith { it.health }
        val spare = object : ExplosionDamageCalculator() {
            override fun shouldDamageEntity(explosion: Explosion, entity: Entity): Boolean =
                (if (guard) rules.guardMayHit(entity) else !aboard(entity) && (entity !is Player || GunSettings.gunnerHurtsPlayers)) &&
                    super.shouldDamageEntity(explosion, entity)

            override fun getKnockbackMultiplier(entity: Entity): Float =
                if ((guard && !rules.guardMayHit(entity)) || entity is ArmedVehicle) 0f else super.getKnockbackMultiplier(entity)
        }
        val (griefing, interaction) = when {
            guard -> false to Level.ExplosionInteraction.NONE
            GunSettings.blastGriefing == GunSettings.BlastGriefing.ALWAYS -> true to Level.ExplosionInteraction.TNT
            GunSettings.blastGriefing == GunSettings.BlastGriefing.NEVER -> false to Level.ExplosionInteraction.NONE
            else -> level.gameRules.getBoolean(GameRules.RULE_MOBGRIEFING) to Level.ExplosionInteraction.MOB
        }
        // Its bang is each client's own, late with the distance (ShellBursts): the game's, heard at once, is left out.
        level.explode(this, source(level), spare, at.x, at.y, at.z, blastPower, griefing, interaction,
            ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, Holder.direct(SoundEvents.EMPTY))
        for ((hurt, health) in alive) {
            if (hurt.isAlive && hurt.health >= health) continue
            hits++
            if (hurt.isAlive) hurt.igniteForSeconds(fireSeconds) else kills++
        }
    }

    private fun source(level: ServerLevel): DamageSource = GunDamage.source(level, VehicleGuns.SHELL_DAMAGE, this, owner)

    companion object {
        /** It is drawn from this many ticks after it is fired, out of the muzzle's flash. */
        const val SHOWN_AFTER = 1
        /** Its burst is sent to players this near, blocks. */
        private const val SEEN_FROM = 128.0
        private val GRAVITY: EntityDataAccessor<Float> = SynchedEntityData.defineId(ShellEntity::class.java, EntityDataSerializers.FLOAT)
    }
}
