package dev.vehicleguns.gun

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.SeekerSpec
import dev.vehicleguns.api.entity
import dev.vehicleguns.ballistics.Ballistics
import dev.vehicleguns.ballistics.VehicleFrame
import dev.vehicleguns.entity.SeekerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A vehicle's homing rounds ([SeekerSpec]). Under a gunner they launch while the key is held, by turns from each
 * launcher, at what the gunner marks, else the nearest hostile mob near where they look, else along their sight (a
 * round finds a hostile mob near its way itself). Guarding, they go at the nearest hostile mob in reach and in sight,
 * more slowly. Each spends stamina; spent, none go until it has come back far enough.
 */
internal object Seekers {
    /** A held key stands this many ticks after the client last said so: it says so every tick it is held. */
    const val HELD_TICKS = 4L
    /** Guarding, no faster than one every this many ticks. */
    private const val GUARD_GAP = 12L
    private const val SEARCH_TICKS = 10L
    /** The gunner's sight picks the nearest hostile mob within this many degrees of it when they mark nothing. */
    private const val SIGHT_DEGREES = 25.0
    /** Stamina is told to clients in steps this fine. */
    private const val STAMINA_STEPS = 50.0
    /** A guard's current target is kept over a nearer one unless that one is this much nearer, blocks squared. */
    private const val KEEP_BONUS = 9.0

    fun tick(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: Player?, now: Long) {
        val spec = vehicle.turretSpec.seekers ?: return
        val pivot = Aiming.pivot(vehicle)
        val held = gunner != null && now <= gun.seekUntil
        val mark: Entity? = when {
            gunner != null -> if (held) gunnersMark(level, vehicle, gun, gunner, spec, pivot, now) else null
            vehicle.turretSpec.guard != null -> guardsMark(level, vehicle, gun, spec, pivot, now)
            else -> null
        }
        val gap = if (gunner != null) spec.gap.toLong() else maxOf(spec.gap.toLong(), GUARD_GAP)
        val wants = held || mark != null
        val was = gun.stamina
        val wasSpent = gun.spent
        if (wants && !gun.spent && now - gun.seekFiredAt >= gap && gun.stamina >= spec.cost) {
            launch(level, vehicle, gun, gunner, spec, mark)
            gun.seekFiredAt = now
            gun.stamina -= spec.cost
            if (gun.stamina < spec.cost) gun.spent = true
        } else if (!wants) {
            gun.stamina = minOf(1.0, gun.stamina + 1.0 / (spec.regenSeconds * 20.0))
        }
        if (gun.spent && gun.stamina >= spec.resume) gun.spent = false
        if (Math.floor(gun.stamina * STAMINA_STEPS) != Math.floor(was * STAMINA_STEPS) || gun.spent != wasSpent) gun.weaponsChanged = true
    }

    /** Whether a round of [kind] may make for [mob]: in the water for a torpedo, not wholly under it for a missile. */
    private fun markable(kind: SeekerSpec.Kind, mob: Entity, vehicle: ArmedVehicle, byGunner: Boolean): Boolean {
        val ship = vehicle.entity
        if (!mob.isAlive || mob === ship || mob.rootVehicle === ship) return false
        val rules = vehicle.targetRules
        if (if (byGunner) !rules.gunnerMayHit(mob) else !rules.guardMayHit(mob)) return false
        return if (kind == SeekerSpec.Kind.TORPEDO) mob.isInWater else !SeekerEntity.submerged(mob.level(), mob)
    }

    private fun gunnersMark(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: Player, spec: SeekerSpec, pivot: Vec3, now: Long): Entity? {
        level.getEntity(gun.seekMark)?.takeIf { markable(spec.kind, it, vehicle, true) && it.distanceToSqr(pivot) <= spec.range * spec.range }?.let { return it }
        val look = sight(level, vehicle, gun, gunner, now).subtract(pivot).normalize()
        return level.getEntitiesOfClass(Mob::class.java, AABB(pivot, pivot).inflate(spec.range)) { markable(spec.kind, it, vehicle, false) }
            .filter { Ballistics.apart(look, Aiming.aimPoint(it).subtract(pivot).normalize()) <= SIGHT_DEGREES }
            .minByOrNull { it.distanceToSqr(pivot) }
    }

    private fun guardsMark(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, spec: SeekerSpec, pivot: Vec3, now: Long): Entity? {
        fun fits(mob: Entity) = markable(spec.kind, mob, vehicle, false) && mob.distanceToSqr(pivot) <= spec.range * spec.range && inSight(level, pivot, mob)
        var own = level.getEntity(gun.seekTarget)?.takeIf { fits(it) }
        if (own == null || (now + vehicle.entity.id) % SEARCH_TICKS == 0L) {
            val current = own
            own = level.getEntitiesOfClass(Mob::class.java, AABB(pivot, pivot).inflate(spec.range)) { fits(it) }
                .minByOrNull { it.distanceToSqr(pivot) - if (it === current) KEEP_BONUS else 0.0 }
        }
        gun.seekTarget = own?.id ?: -1
        return own
    }

    /** Where the gunner's sight is: the spot their client holds, else the server's own guess from their view. */
    private fun sight(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: Player, now: Long): Vec3 =
        gun.heldAim?.takeIf { now - gun.heldAimAt <= Turrets.HELD_AIM_TICKS } ?: Aiming.aimedAt(level, vehicle, gunner).spot

    private fun inSight(level: ServerLevel, from: Vec3, mob: Entity): Boolean =
        level.clip(ClipContext(from, Aiming.aimPoint(mob), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob)).type == HitResult.Type.MISS

    /** One round from the next launcher, along the launch direction, gathering way from a share of its speed. */
    private fun launch(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: Player?, spec: SeekerSpec, mark: Entity?) {
        if (spec.launchers.isEmpty()) return
        val e = vehicle.entity
        val heading = e.yRot.toDouble()
        val at = VehicleFrame.point(e.position(), heading, spec.launchers[gun.seekNext++ % spec.launchers.size])
        val dir = VehicleFrame.way(heading, spec.launchDirection).normalize()
        val round = SeekerEntity(VehicleGuns.SEEKER, level)
        round.owner = gunner ?: e
        round.arm(spec, vehicle.targetRules, guard = gunner == null, vehicle = e, target = mark?.id ?: -1)
        round.setPos(at)
        round.deltaMovement = dir.scale(spec.speed * spec.launchShare).add(gun.vehicleVelocity)
        level.addFreshEntity(round)
        if (spec.kind == SeekerSpec.Kind.TORPEDO) {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.DISPENSER_LAUNCH, SoundSource.NEUTRAL, 1.0f, 0.6f)
            level.playSound(null, at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundSource.NEUTRAL, 0.8f, 1.4f)
        } else {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 1.6f, 0.7f + level.random.nextFloat() * 0.1f)
        }
    }
}
