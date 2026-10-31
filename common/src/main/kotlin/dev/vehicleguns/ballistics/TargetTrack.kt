package dev.vehicleguns.ballistics

import net.minecraft.world.phys.Vec3

/**
 * What a gunner makes of a moving target from where it is seen each tick: where it is and how fast it is going,
 * smoothed with an alpha-beta tracker so a mob's stop-start shuffle does not throw the gun about, and so where it will
 * be in a few ticks can be told ([ahead]). Each sighting pulls the estimate [ALPHA] of the way to it and its velocity
 * [BETA] of the surprise.
 */
class TargetTrack(var at: Vec3, var velocity: Vec3 = Vec3.ZERO) {
    fun see(seen: Vec3, alpha: Double = ALPHA, beta: Double = BETA) {
        val guess = at.add(velocity)
        val surprise = seen.subtract(guess)
        at = guess.add(surprise.scale(alpha))
        velocity = velocity.add(surprise.scale(beta))
    }

    /** Starts afresh from [seen], standing still. */
    fun reset(seen: Vec3) {
        at = seen
        velocity = Vec3.ZERO
    }

    /** Where it will be [ticks] from now, going on as it is. */
    fun ahead(ticks: Double): Vec3 = at.add(velocity.scale(ticks))

    companion object {
        /** Critically damped (beta = alpha^2 / (2 - alpha)): it settles on a steady walker in a few ticks without ringing. */
        const val ALPHA = 0.5
        const val BETA = ALPHA * ALPHA / (2 - ALPHA)
    }
}
