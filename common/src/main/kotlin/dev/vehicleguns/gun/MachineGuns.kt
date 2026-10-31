package dev.vehicleguns.gun

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.MachineGunSpec
import dev.vehicleguns.api.entity
import dev.vehicleguns.ballistics.Ballistics
import dev.vehicleguns.ballistics.VehicleFrame
import dev.vehicleguns.entity.GunDamage
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * A vehicle's machine guns ([MachineGunSpec]). Each turns within its cone, at a steady pace, towards where its target
 * will be when a bullet gets there, and rests along its own line with nothing to shoot. Guarding, it picks hostile mobs
 * within its range and fires bursts at them; while the gunner holds the key, every gun whose reach takes in the
 * crosshair turns to it and fires full auto at whatever living thing is first on its line, and spends the guns' heat.
 *
 * A bullet is not an entity: it is fired down the barrel off by the gun's spread, and lands when it would have flown
 * that far, on whatever is on its line then. Clients draw it as a tracer from the muzzle to where the server says it
 * went ([Turret.machineGunEnd]).
 */
internal object MachineGuns {
    /** The gunner's hand on the guns lapses when their word has not come for this many ticks (let go). */
    private const val MANUAL_FRESH = 4L
    /** Laid within this of where it wants to point, degrees, it fires. */
    private const val ON_DEGREES = 1.5
    /** Stowed after this long with nothing to shoot, it takes [UNSTOW_TICKS] to come out again before it fires. */
    private const val STOW_TICKS = 60L
    private const val UNSTOW_TICKS = 6L
    /** A bullet that misses flies on this far past its target before it is spent. */
    private const val CARRY = 6.0
    private const val SEARCH_TICKS = 10L

    fun tick(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: Player?, now: Long) {
        val specs = vehicle.turretSpec.machineGuns
        if (specs.isEmpty()) return
        if (gun.machineGuns.size != specs.size) gun.machineGuns = Array(specs.size) { MachineGunState() }
        val e = vehicle.entity
        val heading = e.yRot.toDouble()
        val crosshair = gun.machineGunAim?.takeIf { gunner != null && now - gun.machineGunAimAt <= MANUAL_FRESH && !gun.cooling }
        var changed = false
        for ((i, spec) in specs.withIndex()) {
            val mg = gun.machineGuns[i]
            val pivot = VehicleFrame.point(e.position(), heading, spec.mount)
            val rest = VehicleFrame.way(heading, spec.rest).normalize()
            val manual = crosshair?.takeIf { it.distanceTo(pivot) <= spec.gunnerRange && Ballistics.apart(rest, it.subtract(pivot).normalize()) <= spec.cone }
            if ((manual != null) != mg.manual) { mg.manual = manual != null; changed = true }

            var target = if (manual != null) null else level.getEntity(mg.target)?.takeIf { reaches(level, vehicle, spec, pivot, rest, it) }
            if (manual == null && (target == null || (now + e.id + i * 5) % SEARCH_TICKS == 0L)) {
                target = pick(level, vehicle, gun, spec, pivot, rest, i, target)
            }
            val id = target?.id ?: -1
            if (id != mg.target) { mg.target = id; changed = true }
            if (target != null || manual != null) {
                if (now - mg.lastAimed > STOW_TICKS) mg.readyAt = now + UNSTOW_TICKS
                mg.lastAimed = now
            }

            // Where it wants to point: the crosshair, or where its target will be when a bullet gets there, within its cone.
            val want = manual?.subtract(pivot)?.normalize() ?: target?.let { mob ->
                val track = gun.track(mob, now)
                var aim = track.at
                repeat(LEAD_PASSES) { aim = track.ahead(pivot.distanceTo(aim) / spec.bulletSpeed) }
                val dir = aim.subtract(pivot).normalize()
                if (Ballistics.apart(rest, dir) > spec.cone) VehicleFrame.turnToward(rest, dir, spec.cone) else dir
            } ?: rest
            val was = mg.lie?.let { VehicleFrame.way(heading, it).normalize() } ?: rest
            val lies = VehicleFrame.turnToward(was, want, spec.turnDegrees)
            if (mg.lie == null || Ballistics.apart(was, lies) > 0.05) { mg.lie = VehicleFrame.local(heading, lies); changed = true }
            val ready = now >= mg.readyAt && Ballistics.apart(lies, want) <= ON_DEGREES
            val muzzle = pivot.add(lies.scale(spec.barrel))

            if (manual != null) {
                mg.burstLeft = 0
                if (ready && now - mg.firedAt >= spec.autoGap) {
                    mg.firedAt = now
                    fire(level, vehicle, spec, mg, muzzle, lies, muzzle.distanceTo(manual) + CARRY, now, manual = true)
                    changed = true
                }
            } else if (target != null) {
                if (ready && now - mg.firedAt >= spec.burstReload && mg.burstLeft == 0) {
                    mg.firedAt = now
                    mg.burstLeft = spec.burst
                }
                if (mg.burstLeft > 0 && (now - mg.firedAt) % spec.burstGap == 0L) {
                    mg.burstLeft--
                    fire(level, vehicle, spec, mg, muzzle, lies, muzzle.distanceTo(gun.track(target, now).at) + CARRY, now, manual = false)
                    changed = true
                }
            } else {
                mg.burstLeft = 0
            }
            land(level, vehicle, spec, mg, now)
        }
        if (vehicle.turretSpec.heat != null && heat(vehicle, gun)) changed = true
        if (changed) gun.weaponsChanged = true
    }

    private const val LEAD_PASSES = 2

    /** Whether [mob] is one the gun at [pivot] may fire on: allowed, in range, within its cone, and the line to it clear. */
    private fun reaches(level: ServerLevel, vehicle: ArmedVehicle, spec: MachineGunSpec, pivot: Vec3, rest: Vec3, mob: Entity): Boolean {
        if (!vehicle.targetRules.guardMayHit(mob) || mob.level() !== level) return false
        val to = Aiming.aimPoint(mob)
        if (to.distanceToSqr(pivot) > spec.range * spec.range) return false
        val dir = to.subtract(pivot).normalize()
        return Ballistics.apart(rest, dir) <= spec.cone && Aiming.clearPath(level, vehicle, pivot.add(dir.scale(spec.barrel)), to, mob)
    }

    /** The nearest mob it may fire on, leaving what the main gun or another machine gun is on to them where it can. */
    private fun pick(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, spec: MachineGunSpec, pivot: Vec3, rest: Vec3, index: Int, current: Entity?): Entity? {
        val others = gun.machineGuns.withIndex().filter { it.index != index }.map { it.value.target }.toSet()
        return level.getEntitiesOfClass(Mob::class.java, AABB(pivot, pivot).inflate(spec.range)) { reaches(level, vehicle, spec, pivot, rest, it) }
            .minByOrNull { mob ->
                var score = Aiming.aimPoint(mob).distanceTo(pivot)
                if (mob.id == gun.target) score += MAIN_GUNS_TARGET
                if (mob.id in others) score += OTHER_GUNS_TARGET
                if (mob === current) score -= CURRENT_BONUS
                score
            }
    }

    private const val MAIN_GUNS_TARGET = 4.0
    private const val OTHER_GUNS_TARGET = 6.0
    private const val CURRENT_BONUS = 3.0

    /** A bullet down the barrel along [lies], off it by the gun's spread, landing when it would have flown to the first block or [far]. */
    private fun fire(level: ServerLevel, vehicle: ArmedVehicle, spec: MachineGunSpec, mg: MachineGunState, muzzle: Vec3, lies: Vec3, far: Double, now: Long, manual: Boolean) {
        mg.shots++
        val dir = spread(lies, spec.spread, level.random)
        val wall = level.clip(ClipContext(muzzle, muzzle.add(dir.scale(far)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle.entity)).location
        val end = struck(level, vehicle, muzzle, wall, manual)?.second ?: wall
        mg.lastEnd = end
        val ticks = Math.ceil(muzzle.distanceTo(end) / spec.bulletSpeed).toLong().coerceAtLeast(1)
        mg.inFlight.add(MachineGunState.Bullet(now + ticks, muzzle, dir, muzzle.distanceTo(wall), manual))
    }

    /** Bullets due now land on whatever is first on their line then. */
    private fun land(level: ServerLevel, vehicle: ArmedVehicle, spec: MachineGunSpec, mg: MachineGunState, now: Long) {
        if (mg.inFlight.isEmpty()) return
        val it = mg.inFlight.iterator()
        while (it.hasNext()) {
            val bullet = it.next()
            if (bullet.lands > now) continue
            it.remove()
            val (mob, _) = struck(level, vehicle, bullet.from, bullet.from.add(bullet.dir.scale(bullet.reach)), bullet.manual) ?: continue
            // Every round of a burst counts: the half second a blow leaves a mob unhurtable is let go.
            mob.invulnerableTime = 0
            val firer = if (bullet.manual) vehicle.gunner ?: vehicle.entity else vehicle.entity
            mob.hurt(GunDamage.source(level, VehicleGuns.BULLET_DAMAGE, vehicle.entity, firer), if (bullet.manual) spec.gunnerDamage else spec.guardDamage)
        }
    }

    /** The first creature on the line [from] to [to] a bullet may hit: a gunner's takes any living thing not aboard, a guard's a hostile mob. */
    private fun struck(level: ServerLevel, vehicle: ArmedVehicle, from: Vec3, to: Vec3, manual: Boolean): Pair<LivingEntity, Vec3>? {
        val rules = vehicle.targetRules
        val ship = vehicle.entity
        return level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, to).inflate(1.0)) {
            it.rootVehicle !== ship && (if (manual) rules.gunnerMayHit(it) else rules.guardMayHit(it))
        }.mapNotNull { mob -> mob.boundingBox.inflate(HIT_SLACK).clip(from, to).orElse(null)?.let { mob to it } }
            .minByOrNull { it.second.distanceToSqr(from) }
    }

    private const val HIT_SLACK = 0.1

    /** [dir] turned off its line at random, up to [degrees], evenly over the cone. */
    private fun spread(dir: Vec3, degrees: Double, random: RandomSource): Vec3 {
        val side = dir.cross(if (abs(dir.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).normalize()
        val up = dir.cross(side)
        val r = Math.tan(Math.toRadians(degrees)) * Math.sqrt(random.nextDouble())
        val turn = random.nextDouble() * 2 * Math.PI
        return dir.add(side.scale(r * Math.cos(turn))).add(up.scale(r * Math.sin(turn))).normalize()
    }

    /** Firing by hand spends the heat over its hold; otherwise it comes back over its cool. True when it changed. */
    private fun heat(vehicle: ArmedVehicle, gun: Gunnery): Boolean {
        val spec = vehicle.turretSpec.heat ?: return false
        val was = gun.heat
        val wasCooling = gun.cooling
        gun.heat = if (gun.machineGuns.any { it.manual }) gun.heat - 1.0 / (spec.holdSeconds * 20.0) else gun.heat + 1.0 / (spec.coolSeconds * 20.0)
        gun.heat = gun.heat.coerceIn(0.0, 1.0)
        if (gun.heat <= 0.0) gun.cooling = true
        if (gun.cooling && gun.heat >= 1.0) gun.cooling = false
        // Told to clients a step at a time, not every tick it moves.
        return Math.floor(gun.heat * HEAT_STEPS) != Math.floor(was * HEAT_STEPS) || gun.cooling != wasCooling
    }

    private const val HEAT_STEPS = 50.0
}
