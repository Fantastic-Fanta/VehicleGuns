package dev.vehicleguns.gun

import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.entity
import dev.vehicleguns.ballistics.Ballistics
import dev.vehicleguns.ballistics.DeckClearance
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.max

/**
 * Aiming a vehicle's gun, the same on the server and on the gunner's client: where the gun and the gunner's camera
 * are, what the sight is on, and how the gun is laid onto a spot. The client works it out each frame to draw the sight;
 * the server to lay the gun.
 */
object Aiming {
    /** How far the sight picks a spot: past the gun's reach, so a spot out of reach is still a spot. */
    const val SIGHT_RANGE = 256.0

    /** Direct fire is clear when it comes down within this of the spot, blocks, or this share of the range if more. */
    private const val CLEAR_WITHIN = 2.0
    private const val CLEAR_SHARE = 0.05
    /** The camera pulled in nearer than this goes up over the vehicle instead ([OVER_UP] up, [OVER_BACK] back), where that is further out. */
    private const val NEAR_BACK = 4.0
    private const val OVER_UP = 5.0
    private const val OVER_BACK = 2.5
    private const val CAMERA_MARGIN = 0.3
    private const val HIGHEST_LOOK_DOWN = 75.0

    /** The gun's pivot in the world. */
    fun pivot(vehicle: ArmedVehicle, partial: Float = 1f): Vec3 {
        val e = vehicle.entity
        val spec = vehicle.turretSpec
        return e.getPosition(partial).add(Ballistics.forward(e.yRot.toDouble()).scale(spec.pivotForward)).add(0.0, spec.pivotUp, 0.0)
    }

    /** Which way the barrel points now. */
    fun barrel(vehicle: ArmedVehicle): Vec3 =
        Ballistics.direction(vehicle.entity.yRot.toDouble(), vehicle.turret.traverse.toDouble(), vehicle.turret.elevation.toDouble())

    /** The muzzle, where a round leaves, as the barrel lies now. */
    fun muzzle(vehicle: ArmedVehicle): Vec3 = pivot(vehicle).add(barrel(vehicle).scale(vehicle.turretSpec.barrel))

    /** Whoever rides [vehicle] but its gunner, as columns the barrel must pass over. */
    fun riders(vehicle: ArmedVehicle): List<DeckClearance.Rider> = vehicle.entity.passengers.filter { it !== vehicle.gunner }.map {
        DeckClearance.Rider(it.position(), it.bbHeight + 0.1, it.bbWidth * 0.5 + 0.05)
    }

    /** The lowest the gun may lie at [traverse], over the vehicle's own limit and over [riders]. */
    fun floor(vehicle: ArmedVehicle, traverse: Double, riders: List<DeckClearance.Rider>, pivot: Vec3): Double {
        val spec = vehicle.turretSpec
        return DeckClearance.floor(vehicle.entity.yRot.toDouble(), traverse, pivot, spec.barrel, spec.barrelRadius, riders, spec.lowest)
    }

    /**
     * The gunner's camera for a look of [lookYaw], [lookPitch]: behind and over the gun ([dev.vehicleguns.api.CameraSpec]),
     * swinging round the vehicle as they look about, kept short of any block between it and the gun. Looking up does
     * not swing it down to the ground, so a target up a bank stays in sight. Pressed close against a wall, it goes up
     * over the vehicle instead where that is further out, unless the gunner sits inside the turret.
     */
    fun sight(vehicle: ArmedVehicle, lookYaw: Float, lookPitch: Float, partial: Float = 1f, level: Level? = vehicle.entity.level()): Vec3 {
        val e = vehicle.entity
        val camera = vehicle.turretSpec.camera
        val centre = e.getPosition(partial).add(0.0, vehicle.turretSpec.pivotUp + camera.up, 0.0)
        val y = Math.toRadians(lookYaw.toDouble())
        val x = Math.toRadians(lookPitch.toDouble().coerceIn(0.0, HIGHEST_LOOK_DOWN))
        val look = Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x))
        val want = centre.subtract(look.scale(camera.back)).add(0.0, camera.rise, 0.0)
        if (level == null) return want
        val pulled = shortOf(level, centre, want, e)
        if (pulled.distanceTo(centre) >= NEAR_BACK || vehicle.turretSpec.hideGunner) return pulled
        val top = shortOf(level, centre, centre.add(0.0, OVER_UP, 0.0), e)
        val flat = Vec3(look.x, 0.0, look.z).let { if (it.lengthSqr() < 1.0e-8) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val over = shortOf(level, top, top.subtract(flat.scale(OVER_BACK)), e)
        return if (over.distanceTo(centre) > pulled.distanceTo(centre)) over else pulled
    }

    /** [to], or short of the first block between [from] and it. */
    private fun shortOf(level: Level, from: Vec3, to: Vec3, ignoring: Entity): Vec3 {
        val met = level.clip(ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ignoring))
        if (met.type == HitResult.Type.MISS) return to
        return from.add(to.subtract(from).normalize().scale(max(0.0, met.location.distanceTo(from) - CAMERA_MARGIN)))
    }

    /** What the gunner's sight is on: the spot the gun is laid on, the creature on it if one is, and whether the sight met anything. */
    data class Aim(val spot: Vec3, val creature: Entity?, val met: Boolean)

    /**
     * What [gunner]'s sight is on along [look] from [eye]: the first creature a round may hurt (at its feet, or on the
     * water over it, where a round comes down), else the first block or water surface. Meeting nothing, the spot is
     * as far as the gun reaches along that bearing, level with the vehicle.
     */
    fun aimedFrom(level: Level, vehicle: ArmedVehicle, gunner: Player, eye: Vec3, look: Vec3, range: Double = SIGHT_RANGE): Aim {
        val end = eye.add(look.scale(range))
        val block = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, gunner))
        // Creatures are looked for through water as well: a swimmer sits mostly under the surface the sight meets first.
        val solid = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, gunner))
        val solidEnd = if (solid.type == HitResult.Type.MISS) end else solid.location
        val ship = vehicle.entity
        val rules = vehicle.targetRules
        // Creatures only as far as a round carries, and a little: past that the box swept for them grows huge on a diagonal look.
        val creatureEnd = eye.add(solidEnd.subtract(eye).let { it.scale(minOf(1.0, (vehicle.turretSpec.round.reach + CREATURE_SLACK) / max(it.length(), 1.0e-6))) })
        val hit = ProjectileUtil.getEntityHitResult(level, gunner, eye, creatureEnd, AABB(eye, creatureEnd).inflate(1.0)) {
            it.isPickable && it !== ship && it.rootVehicle !== ship && rules.gunnerMayHit(it)
        }
        if (hit != null) return Aim(standing(level, hit.entity), hit.entity, true)
        if (block.type != HitResult.Type.MISS) return Aim(block.location, null, true)
        val pivot = pivot(vehicle)
        val flat = Vec3(look.x, 0.0, look.z).let { if (it.lengthSqr() < 1.0e-8) Ballistics.forward(ship.yRot.toDouble()) else it.normalize() }
        return Aim(pivot.add(flat.scale(vehicle.turretSpec.round.reach)).with(net.minecraft.core.Direction.Axis.Y, ship.y), null, false)
    }

    /**
     * What [gunner]'s sight is on from their own camera: the server's guess when their client has not said. Looked for
     * only as far as the gun reaches, so the server's ray never runs out into chunks nobody has loaded.
     */
    fun aimedAt(level: Level, vehicle: ArmedVehicle, gunner: Player): Aim =
        aimedFrom(level, vehicle, gunner, sight(vehicle, gunner.yRot, gunner.xRot), gunner.lookAngle, vehicle.turretSpec.round.reach + CREATURE_SLACK)

    private const val CREATURE_SLACK = 16.0

    /** Where [creature] stands, for a round to come down on: its feet, or the water's surface over them when it swims. */
    fun standing(level: Level, creature: Entity): Vec3 {
        val feet = creature.position()
        var pos = BlockPos.containing(feet)
        if (level.getFluidState(pos).isEmpty) return feet
        var steps = 0
        while (steps++ < 16 && !level.getFluidState(pos.above()).isEmpty) pos = pos.above()
        val surface = pos.y + level.getFluidState(pos).getHeight(level, pos).toDouble()
        return Vec3(feet.x, max(feet.y, surface), feet.z)
    }

    /** Where to aim on [target]: its middle, or its head when its middle is under water. */
    fun aimPoint(target: Entity): Vec3 {
        val middle = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
        if (target.level().getFluidState(BlockPos.containing(middle)).isEmpty) return middle
        return target.position().add(0.0, target.bbHeight * 0.85, 0.0)
    }

    /**
     * The lay from [vehicle]'s gun onto [aim], as a naval gun lays: the flat arc, quick and low, laid no flatter than
     * the round's direct lowest nor the gun's floor at that bearing ([floorAt]); and the lob, high over whatever is in
     * the way, only when the flat arc would come down on something short of the spot. Second: whether it is the lob.
     */
    fun chooseLay(vehicle: ArmedVehicle, aim: Vec3, pivot: Vec3, vehicleVelocity: Vec3, floorAt: (Double) -> Double): Pair<Ballistics.Arc, Boolean> {
        val e = vehicle.entity
        val round = vehicle.turretSpec.round
        val barrel = vehicle.turretSpec.barrel
        val heading = e.yRot.toDouble()
        val forward = Ballistics.forward(heading)
        val toward = aim.subtract(pivot).let { if (it.lengthSqr() < 1.0e-8) forward else it.normalize() }
        val floor = floorAt(Ballistics.angles(heading, toward).first)
        val highest = minOf(round.highest, vehicle.turretSpec.highest)
        val flat = Ballistics.arcUnderWay(pivot, barrel, aim, vehicleVelocity, round.speed, round.gravity, round.lifeTicks,
            max(round.directLowest, floor), highest, forward, flat = true)
        if (flat.reaches && clearFlight(e.level(), vehicle, pivot, flat, vehicleVelocity, aim)) return flat to false
        val lob = Ballistics.arcUnderWay(pivot, barrel, aim, vehicleVelocity, round.speed, round.gravity, round.lifeTicks,
            max(round.lobLowest, floor), highest, forward)
        // No lob over it: direct fire into whatever is in the way, the gunner's call. Neither reaching, the flat lay lies low.
        return if (lob.reaches) lob to true else flat to false
    }

    /**
     * Whether a round from [muzzle] would get to [target] at [to]: nothing solid in the way, and nobody friendly
     * ([dev.vehicleguns.api.TargetRules.friendly]) within [FRIEND_CLEARANCE] of its line.
     */
    fun clearPath(level: Level, vehicle: ArmedVehicle, muzzle: Vec3, to: Vec3, target: Entity): Boolean {
        if (level.clip(ClipContext(muzzle, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).type != HitResult.Type.MISS) return false
        val path = to.subtract(muzzle)
        val length = path.length()
        val along = path.scale(1.0 / max(length, 1.0e-6))
        val ship = vehicle.entity
        val rules = vehicle.targetRules
        return level.getEntities(target, AABB(muzzle, to).inflate(FRIEND_CLEARANCE + 1.0)) { it !== ship && rules.friendly(it) }.none { other ->
            val c = other.boundingBox.center.subtract(muzzle)
            val s = c.dot(along).coerceIn(0.0, length)
            c.subtract(along.scale(s)).length() < FRIEND_CLEARANCE + other.bbWidth * 0.5
        }
    }

    private const val FRIEND_CLEARANCE = 0.5

    /** Whether a round laid [lay] comes down near [aim] rather than on something on the way there. */
    private fun clearFlight(level: Level, vehicle: ArmedVehicle, pivot: Vec3, lay: Ballistics.Arc, vehicleVelocity: Vec3, aim: Vec3): Boolean {
        val round = vehicle.turretSpec.round
        val down = Ballistics.landing(pivot, vehicle.turretSpec.barrel, lay.dir, vehicleVelocity, round.speed, round.gravity, round.lifeTicks) { a, b ->
            level.clip(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
                .takeIf { it.type != HitResult.Type.MISS }?.location
        } ?: return true
        val off = Math.hypot(down.x - aim.x, down.z - aim.z)
        return off <= max(CLEAR_WITHIN, Math.hypot(aim.x - pivot.x, aim.z - pivot.z) * CLEAR_SHARE)
    }
}
