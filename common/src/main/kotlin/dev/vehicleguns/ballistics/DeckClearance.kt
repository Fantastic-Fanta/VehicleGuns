package dev.vehicleguns.ballistics

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Keeping the barrel clear of the people riding the vehicle: how high it must be raised at a bearing to pass over
 * them, and which way round the turret should swing so it does not sweep through them.
 */
object DeckClearance {
    private const val STEP_DEGREES = 2.0
    private const val SWING_SAMPLE_DEGREES = 6.0
    private const val BARREL_SAMPLES = 12

    /** Someone aboard: a column from [feet] up [height], [radius] round. */
    data class Rider(val feet: Vec3, val height: Double, val radius: Double)

    /**
     * The lowest the gun may be laid at [traverse] with [riders] aboard: its barrel, from [pivot] out [barrel] blocks and
     * [barrelRadius] thick, passing over every one of them, and never below [lowest]. At most straight up.
     */
    fun floor(heading: Double, traverse: Double, pivot: Vec3, barrel: Double, barrelRadius: Double, riders: List<Rider>, lowest: Double): Double {
        if (riders.isEmpty()) return lowest
        var e = lowest
        while (e < 90.0) {
            val tip = pivot.add(Ballistics.direction(heading, traverse, e).scale(barrel))
            if (riders.none { hits(pivot, tip, barrelRadius, it) }) return e
            e += STEP_DEGREES
        }
        return 90.0
    }

    /** Whether the barrel from [from] to [to] passes within [radius] of [rider]. */
    fun hits(from: Vec3, to: Vec3, radius: Double, rider: Rider): Boolean {
        val reach = radius + rider.radius
        for (i in 0..BARREL_SAMPLES) {
            val s = i / BARREL_SAMPLES.toDouble()
            val x = from.x + (to.x - from.x) * s
            val y = from.y + (to.y - from.y) * s
            val z = from.z + (to.z - from.z) * s
            val dx = x - rider.feet.x
            val dz = z - rider.feet.z
            val dy = y - y.coerceIn(rider.feet.y, rider.feet.y + rider.height)
            if (dx * dx + dz * dz + dy * dy < reach * reach) return true
        }
        return false
    }

    /**
     * Which way round the turret should swing from [from] to [to]: +1 to its right, -1 to its left. The short way,
     * unless that runs the barrel over the riders and lifting over them costs more than going round the long way
     * ([floorAt] is the lowest the gun may lie at each traverse).
     */
    fun swingWay(from: Double, to: Double, elevation: Double, topTraverse: Double, topElevation: Double, floorAt: (Double) -> Double): Int {
        val short = Mth.wrapDegrees(to - from)
        val long = short - 360.0 * (if (short == 0.0) 1.0 else sign(short))
        fun cost(way: Double): Double {
            var lift = elevation
            val n = max(1, (abs(way) / SWING_SAMPLE_DEGREES).toInt())
            for (i in 1..n) lift = max(lift, floorAt(from + way * i / n))
            return Ballistics.ticksFor(way, topTraverse) + 2.0 * Ballistics.ticksFor(lift - elevation, topElevation)
        }
        return if (cost(long) < cost(short) - 2.0) sign(long).toInt() else if (short < 0.0) -1 else 1
    }
}
