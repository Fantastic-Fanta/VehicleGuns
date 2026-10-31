package dev.vehicleguns.ballistics

import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The arithmetic of a gun on a moving vehicle, apart from the world: which way a barrel laid at a traverse and an
 * elevation points, where a round fired along it goes, and how to lay it on a moving target or onto a spot.
 *
 * Angles are degrees. Traverse is measured from the vehicle's heading, positive to its right (the way Minecraft's yaw
 * grows); elevation from the horizontal, positive up. A round flies as Minecraft moves a thrown thing: on by its
 * velocity, then slowed by [AIR_DRAG] and pulled down by its gravity, once a tick. A round leaves the muzzle with the
 * vehicle's own velocity added to its own.
 */
object Ballistics {
    /** Drag a round in the air keeps each tick (a thrown thing's). */
    const val AIR_DRAG = 0.99
    /** Blocks off a target a solution may be and still count as on it. */
    const val CLOSE_ENOUGH = 0.3

    private const val SOLVE_TRIES = 6
    /** The arc's elevation is swept this many degrees at a time for its furthest reach, then halved this many times onto its spot. */
    private const val ARC_SWEEP = 3.0
    private const val ARC_HALVINGS = 18

    /** Which way a barrel points, laid at [traverse] and [elevation] on a vehicle facing [heading]. */
    fun direction(heading: Double, traverse: Double, elevation: Double): Vec3 {
        val yaw = Math.toRadians(heading + traverse)
        val e = Math.toRadians(elevation)
        return Vec3(-sin(yaw) * cos(e), sin(e), cos(yaw) * cos(e))
    }

    /** The traverse and elevation that point along [dir] on a vehicle facing [heading]. */
    fun angles(heading: Double, dir: Vec3): Pair<Double, Double> {
        val flat = sqrt(dir.x * dir.x + dir.z * dir.z)
        val elevation = Math.toDegrees(Math.atan2(dir.y, flat))
        val traverse = if (flat < 1.0e-6) 0.0 else Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dir.x, dir.z)) - heading)
        return traverse to elevation
    }

    /** Degrees between two unit directions. */
    fun apart(a: Vec3, b: Vec3): Double = Math.toDegrees(Math.acos(a.dot(b).coerceIn(-1.0, 1.0)))

    /** A unit direction along the ground for [heading] degrees of Minecraft yaw. */
    fun forward(heading: Double): Vec3 {
        val yaw = Math.toRadians(heading)
        return Vec3(-sin(yaw), 0.0, cos(yaw))
    }

    /**
     * A round's flight a tick at a time from [from] at [velocity]: [each] is called with every point it reaches,
     * after [from], until it returns false or [ticks] run out.
     */
    inline fun fly(from: Vec3, velocity: Vec3, gravity: Double, ticks: Int, each: (tick: Int, at: Vec3) -> Boolean) {
        var x = from.x; var y = from.y; var z = from.z
        var vx = velocity.x; var vy = velocity.y; var vz = velocity.z
        for (t in 1..ticks) {
            x += vx; y += vy; z += vz
            vx *= AIR_DRAG; vy = vy * AIR_DRAG - gravity; vz *= AIR_DRAG
            if (!each(t, Vec3(x, y, z))) return
        }
    }

    /** How far a velocity of one block a tick, given at the muzzle, carries a round in [ticks] ticks, with the drag. */
    fun carried(ticks: Int): Double = (1.0 - Math.pow(AIR_DRAG, ticks.toDouble())) / (1.0 - AIR_DRAG)

    /** Where the target is from the round at their closest over one tick: the round from [a0] to [a1], the target from [b0] to [b1]. */
    fun closestOff(a0: Vec3, a1: Vec3, b0: Vec3, b1: Vec3): Vec3 {
        val start = b0.subtract(a0)
        val change = b1.subtract(b0).subtract(a1.subtract(a0))
        val len = change.lengthSqr()
        val s = if (len < 1.0e-12) 0.0 else (-start.dot(change) / len).coerceIn(0.0, 1.0)
        return start.add(change.scale(s))
    }

    /** Where a straight shot from [from] along [dir] first meets [box] (grown by [slack]) within [reach], or null. */
    fun meets(from: Vec3, dir: Vec3, reach: Double, box: AABB, slack: Double): Vec3? =
        box.inflate(slack).clip(from, from.add(dir.scale(reach))).orElse(null)

    /** A way to lay the gun on a target: where to point it, the ticks its round flies, and by how far it still misses. */
    data class Solution(val dir: Vec3, val ticks: Int, val miss: Double)

    /**
     * Lays a gun on a target now at [target] going [targetVelocity] a tick, leading it and lifting for the fall. The
     * barrel turns about [pivot] and its muzzle is [barrel] blocks out; the round leaves at [speed] plus
     * [vehicleVelocity]. Each try aims where the last one missed by. Null when no round reaches it in [ticks].
     */
    fun solve(pivot: Vec3, barrel: Double, target: Vec3, targetVelocity: Vec3, vehicleVelocity: Vec3, speed: Double, gravity: Double, ticks: Int): Solution? {
        var aim = target
        var best: Solution? = null
        repeat(SOLVE_TRIES) {
            val dir = aim.subtract(pivot).normalize()
            val muzzle = pivot.add(dir.scale(barrel))
            var nearest = Double.MAX_VALUE
            var nearestTick = -1
            var miss = Vec3.ZERO
            var was = muzzle
            fly(muzzle, dir.scale(speed).add(vehicleVelocity), gravity, ticks) { t, at ->
                val off = closestOff(was, at, target.add(targetVelocity.scale(t - 1.0)), target.add(targetVelocity.scale(t.toDouble())))
                was = at
                val d = off.lengthSqr()
                if (d < nearest) { nearest = d; nearestTick = t; miss = off; true } else false
            }
            if (nearestTick < 0) return best
            val solution = Solution(dir, nearestTick, sqrt(nearest))
            val kept = best?.takeIf { it.miss <= solution.miss } ?: solution
            best = kept
            if (solution.miss < CLOSE_ENOUGH) return solution
            // Still closing when its flight ran out: it never got there.
            if (nearestTick == ticks) return kept.takeIf { it.miss < CLOSE_ENOUGH }
            aim = aim.add(miss)
        }
        return best?.takeIf { it.miss < CLOSE_ENOUGH * 4 }
    }

    /**
     * A lay onto a spot along an arc. [dir] is the barrel's direction; [ticks] how long the round flies; [miss] how far
     * short it falls when the spot is out of reach (0 when reached); [lands] where it comes down; [reach] how far the
     * gun carries at the most, flat, at the spot's height. [near] says the spot is nearer than the arc can come down.
     */
    data class Arc(val dir: Vec3, val ticks: Int, val miss: Double, val lands: Vec3, val reach: Double, val near: Boolean = false) {
        val reaches: Boolean get() = miss <= CLOSE_ENOUGH
    }

    /**
     * Lays the gun on the spot [target] along an arc, elevation between [lowest] and [highest]. Of the two arcs that
     * reach a spot it takes the high one (a lob over whatever is in the way), or with [flat] the low one (direct fire).
     * The bearing is the target's own; a target straight overhead takes [fallback]. Past the gun's reach the lay is
     * the one that carries furthest, short by [Arc.miss]; nearer than it can come down, the nearest, and [Arc.near].
     */
    fun arc(pivot: Vec3, barrel: Double, target: Vec3, vehicleVelocity: Vec3, speed: Double, gravity: Double, ticks: Int,
            lowest: Double, highest: Double, fallback: Vec3, flat: Boolean = false): Arc =
        arc(pivot, barrel, target, vehicleVelocity, speed, gravity, ticks, lowest, highest, fallback, flat, null)

    /**
     * The elevation that carries furthest and how far, for a spot [rise] blocks over the pivot: worked out once and
     * handed to every [arc] for the same height and velocity (the passes of [arcUnderWay] differ only along the ground).
     */
    private class Sweep(val rise: Double, val peakAt: Double, val peak: Double)
    private class SweepMemo { var sweep: Sweep? = null }

    private fun arc(pivot: Vec3, barrel: Double, target: Vec3, vehicleVelocity: Vec3, speed: Double, gravity: Double, ticks: Int,
                    lowest: Double, highest: Double, fallback: Vec3, flat: Boolean, memo: SweepMemo?): Arc {
        val off = target.subtract(pivot)
        val flatLength = sqrt(off.x * off.x + off.z * off.z)
        val bearing = if (flatLength > 1.0e-6) Vec3(off.x / flatLength, 0.0, off.z / flatLength) else Vec3(fallback.x, 0.0, fallback.z).normalize()
        val low = lowest.coerceIn(-89.0, 89.0)
        fun along(elevation: Double): Vec3 {
            val e = Math.toRadians(elevation)
            return Vec3(bearing.x * cos(e), sin(e), bearing.z * cos(e))
        }
        fun out(at: Vec3) = (at.x - pivot.x) * bearing.x + (at.z - pivot.z) * bearing.z

        // How far out a round laid at [elevation] is as it comes down through the target's height, and where; or where
        // its flight ends when it never does. Never rising that high, it is -infinity: short however far it flies.
        fun carry(elevation: Double): Pair<Double, Vec3> {
            val dir = along(elevation)
            val muzzle = pivot.add(dir.scale(barrel))
            var was = muzzle
            var top = muzzle
            var reached: Vec3? = null
            fly(muzzle, dir.scale(speed).add(vehicleVelocity), gravity, ticks) { _, at ->
                if (at.y > top.y) top = at
                if (at.y <= target.y && was.y > target.y && at.y < was.y) {
                    reached = was.add(at.subtract(was).scale(((was.y - target.y) / (was.y - at.y)).coerceIn(0.0, 1.0)))
                    false
                } else { was = at; true }
            }
            if (reached == null && top.y < target.y) return Double.NEGATIVE_INFINITY to top
            val end = reached ?: was
            return out(end) to end
        }

        // How high a round laid at [elevation] is as it passes over the spot, where, and on which tick: the flat arc is
        // laid on this, meeting the spot climbing or falling.
        fun over(elevation: Double): Triple<Double, Vec3, Int> {
            val dir = along(elevation)
            val muzzle = pivot.add(dir.scale(barrel))
            if (out(muzzle) >= flatLength) return Triple(muzzle.y, muzzle, 0)
            var was = muzzle
            var met: Triple<Double, Vec3, Int>? = null
            fly(muzzle, dir.scale(speed).add(vehicleVelocity), gravity, ticks) { t, at ->
                if (out(at) >= flatLength) {
                    val point = was.add(at.subtract(was).scale(((flatLength - out(was)) / (out(at) - out(was))).coerceIn(0.0, 1.0)))
                    met = Triple(point.y, point, t)
                    false
                } else { was = at; true }
            }
            return met ?: Triple(Double.NEGATIVE_INFINITY, was, ticks)
        }

        // The elevation that carries furthest, on a coarse sweep: above it range falls as the gun rises. With no level
        // velocity a flight is the same whatever the bearing, so the sweep holds for any spot at this height.
        val rise = target.y - pivot.y
        val kept = memo?.sweep?.takeIf { abs(it.rise - rise) < 1.0e-9 }
        var peakAt = kept?.peakAt ?: low
        var peak = kept?.peak ?: Double.NEGATIVE_INFINITY
        if (kept == null) {
            var e = low
            while (e <= highest) {
                val range = carry(e).first
                if (range > peak) { peak = range; peakAt = e }
                e += ARC_SWEEP
            }
            memo?.sweep = Sweep(rise, peakAt, peak)
        }

        var flightTicks: Int? = null
        val lay: Double
        val miss: Double
        val lands: Vec3
        var near = false
        val lowOver by lazy { over(low) }
        when {
            flatLength >= peak -> {
                // Beyond its reach: as far as it carries.
                lay = peakAt
                lands = carry(peakAt).second
                miss = if (peak.isInfinite()) lands.distanceTo(target) else flatLength - peak
            }
            flat && lowOver.first >= target.y -> {
                // Nearer than the flattest lay comes down: it passes over the spot.
                val (lowRange, lowEnd) = carry(low)
                lay = low
                lands = lowEnd
                miss = if (lowRange.isInfinite()) lowOver.second.distanceTo(target) else max(0.0, lowRange - flatLength)
                near = miss > CLOSE_ENOUGH
            }
            flat && over(peakAt).first >= target.y -> {
                // Between: halved onto the flat arc that is at the spot's height as it gets there.
                var lo = low
                var hi = peakAt
                var at = (lo + hi) / 2
                var end = target
                repeat(ARC_HALVINGS) {
                    at = (lo + hi) / 2
                    val (height, where, tick) = over(at)
                    end = where
                    flightTicks = tick
                    if (height < target.y) lo = at else hi = at
                }
                lay = at; lands = end; miss = 0.0
            }
            else -> {
                val (topRange, topEnd) = carry(highest)
                if (flatLength <= topRange) {
                    // Nearer than the steepest arc comes down.
                    lay = highest
                    lands = topEnd
                    miss = topRange - flatLength
                    near = miss > CLOSE_ENOUGH
                } else {
                    // Between the furthest lay and the steepest the range falls steadily: halved onto the spot.
                    var lo = peakAt
                    var hi = highest
                    var at = (lo + hi) / 2
                    var end = target
                    repeat(ARC_HALVINGS) {
                        at = (lo + hi) / 2
                        val (range, where) = carry(at)
                        end = where
                        if (range > flatLength) lo = at else hi = at
                    }
                    lay = at; lands = end; miss = 0.0
                }
            }
        }
        val dir = along(lay)
        val flight = flightTicks ?: ticksToFall(pivot, barrel, dir, vehicleVelocity, speed, gravity, ticks, target.y)
        return Arc(dir, flight, miss, lands, peak, near)
    }

    /**
     * [arc] from a vehicle under way. A round's flight with the vehicle's level velocity taken off is the same flight
     * carried [carried] of that velocity sideways, so the gun is laid on the spot moved back against it by that much,
     * worked out again for the flight that lay takes. The landing and miss it gives are the true ones.
     */
    fun arcUnderWay(pivot: Vec3, barrel: Double, target: Vec3, vehicleVelocity: Vec3, speed: Double, gravity: Double, ticks: Int,
                    lowest: Double, highest: Double, fallback: Vec3, flat: Boolean = false): Arc {
        val level = Vec3(vehicleVelocity.x, 0.0, vehicleVelocity.z)
        val rise = Vec3(0.0, vehicleVelocity.y, 0.0)
        val memo = SweepMemo()
        var lay = arc(pivot, barrel, target, rise, speed, gravity, ticks, lowest, highest, fallback, flat, memo)
        if (level.lengthSqr() < 1.0e-8) return lay
        repeat(UNDER_WAY_PASSES) {
            lay = arc(pivot, barrel, target.subtract(level.scale(carried(lay.ticks))), rise, speed, gravity, ticks, lowest, highest, fallback, flat, memo)
        }
        val bearing = Vec3(target.x - pivot.x, 0.0, target.z - pivot.z).let { if (it.lengthSqr() < 1.0e-8) Vec3(fallback.x, 0.0, fallback.z) else it }.normalize()
        val lands = lay.lands.add(level.scale(carried(lay.ticks)))
        val miss = if (lay.reaches) lay.miss else Math.hypot(lands.x - target.x, lands.z - target.z)
        return lay.copy(lands = lands, reach = lay.reach + level.dot(bearing) * carried(lay.ticks), miss = miss, near = lay.near && miss > CLOSE_ENOUGH)
    }

    private const val UNDER_WAY_PASSES = 3

    /** Ticks a round laid along [dir] takes to come down through [floorY], or [ticks] when it never does. */
    private fun ticksToFall(pivot: Vec3, barrel: Double, dir: Vec3, vehicleVelocity: Vec3, speed: Double, gravity: Double, ticks: Int, floorY: Double): Int {
        var landedAt = ticks
        var was = pivot.add(dir.scale(barrel))
        fly(was, dir.scale(speed).add(vehicleVelocity), gravity, ticks) { t, at ->
            if (at.y <= floorY && was.y > floorY) { landedAt = t; false } else { was = at; true }
        }
        return landedAt
    }

    /**
     * Where a round fired along [dir] comes down: the first block it meets, walked tick by tick ([clip] says where a
     * straight run between two points meets a block, or null), or where its flight ends. Null when it meets nothing and
     * is still above the muzzle. Each point of its flight goes into [path] when one is given.
     */
    fun landing(pivot: Vec3, barrel: Double, dir: Vec3, vehicleVelocity: Vec3, speed: Double, gravity: Double, ticks: Int,
                path: MutableList<Vec3>? = null, clip: (Vec3, Vec3) -> Vec3?): Vec3? {
        val muzzle = pivot.add(dir.scale(barrel))
        var was = muzzle
        var hit: Vec3? = null
        path?.add(muzzle)
        fly(muzzle, dir.scale(speed).add(vehicleVelocity), gravity, ticks) { _, at ->
            val met = clip(was, at)
            if (met != null) { hit = met; path?.add(met); false } else { was = at; path?.add(at); true }
        }
        return hit ?: was.takeIf { it.y < muzzle.y }
    }

    /** Ticks a mount topping out at [top] degrees a tick takes to cover [degrees], at the least. */
    fun ticksFor(degrees: Double, top: Double): Double = abs(degrees) / top
}
