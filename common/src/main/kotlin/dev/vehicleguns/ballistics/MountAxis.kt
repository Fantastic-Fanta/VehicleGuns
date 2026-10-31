package dev.vehicleguns.ballistics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * One axis of a gun mount (the turret's turn or the barrel's rise): where it is and how fast it is going, in degrees
 * and degrees a tick. It gathers and loses speed at most `accel` a tick, never passes `top`, and comes to rest on
 * where it is sent rather than overshooting it, as a heavy mount does.
 */
class MountAxis(var at: Double = 0.0, var rate: Double = 0.0) {
    /**
     * Moves a tick towards a goal [gap] degrees away the way it is to go, the goal itself moving [follow] degrees a
     * tick (a target crossing the sights): it keeps pace with that and closes the gap on top of it, so it holds on a
     * moving target rather than trailing it.
     */
    fun step(gap: Double, top: Double, accel: Double, follow: Double = 0.0) {
        // Keeping pace covers [follow]; the rest it closes as fast as it can and still stop in the room: v + (v - a) + ... <= room.
        val off = gap - follow
        val close = sign(off) * max(0.0, (sqrt(accel * accel + 8.0 * accel * abs(off)) - accel) / 2.0)
        val want = (follow + close).coerceIn(-top, top)
        rate += (want - rate).coerceIn(-accel, accel)
        if (abs(gap - rate) < accel) {
            // The last of the way: it settles on the bearing at the pace it moved to get there.
            at += gap
            rate = gap
        } else {
            at += rate
        }
    }

    /** Comes to rest, losing [accel] of its speed a tick. */
    fun coast(accel: Double) {
        rate -= sign(rate) * min(abs(rate), accel)
        at += rate
    }
}
