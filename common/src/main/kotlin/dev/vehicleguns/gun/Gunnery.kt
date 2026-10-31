package dev.vehicleguns.gun

import dev.vehicleguns.ballistics.Ballistics
import dev.vehicleguns.ballistics.MountAxis
import dev.vehicleguns.ballistics.TargetTrack
import net.minecraft.world.phys.Vec3

/** One gun's server-side laying state ([Turrets]), kept on its vehicle's [dev.vehicleguns.api.Turret]. */
class Gunnery {
    val traverse = MountAxis()
    val elevation = MountAxis()

    /** Where the vehicle was last tick, its velocity from that, and its heading (whose turn the mount takes back under a gunner). */
    var vehicleWas: Vec3? = null
    var vehicleVelocity: Vec3 = Vec3.ZERO
    var headingWas: Float? = null

    /** The way round the turret is swinging (+1 right, -1 left), and the goal last tick with the pace it is moving at. */
    var way = 0
    var lastGoal: Pair<Double, Double>? = null
    var followTraverse = 0.0
    var followElevation = 0.0

    var firedAt = NEVER

    // Under a gunner.
    /** The lay on the gunner's spot this tick, and whether it is the lob. */
    var lay: Ballistics.Arc? = null
    var lob = false
    /** The spot the gunner's client says it holds the sight on, and when it said so. */
    var heldAim: Vec3? = null
    var heldAimAt = NEVER
    /** Until when a trigger pull waits for the gun, and whether it was pulled with the spot out of reach. */
    var pullUntil = NEVER
    var pullFar = false
    /** Whether the gunner asks for a steady platform (looking down the sight, or locked on). */
    var steady = false

    // Guarding on its own.
    var target = -1
    var lastSeenAt = NEVER
    var blockedSince = 0L
    var idleSince = 0L
    var watchFrom = NEVER
    var watchPhase = 0.0
    var autoLob = false
    val tracks = HashMap<Int, TargetTrack>()
    val sighted = HashMap<Int, Long>()

    /** The last lay worked out, kept while what it was worked out for stands still ([LayCache]). */
    val layCache = LayCache()

    /** Back to a gun nobody lays. */
    fun dropGunner() {
        headingWas = null
        heldAim = null
        lay = null
        pullUntil = NEVER
        steady = false
    }

    fun dropTarget() {
        target = -1
        way = 0
        lastGoal = null
        followTraverse = 0.0
        followElevation = 0.0
    }

    companion object {
        /** A time long before anything happened, safe to subtract from. */
        const val NEVER = Long.MIN_VALUE / 4
    }
}

/**
 * A lay is a few thousand simulated flight ticks and as many block raycasts, worked out for a spot, a vehicle velocity
 * and a heading. A gunner holding the sight still asks for the same one every tick: it is kept while those move less
 * than a hair, and for no more than [MAX_AGE] ticks so the world changing under it is still seen.
 */
class LayCache {
    private var aim: Vec3? = null
    private var velocity = Vec3.ZERO
    private var lead = Vec3.ZERO
    private var heading = 0f
    private var at = Gunnery.NEVER
    private var value: Pair<Ballistics.Arc, Boolean>? = null

    /** The lay for [aim] (a target's [lead] velocity on top), from [velocity] and [heading]: kept, or worked out by [work]. */
    fun get(now: Long, aim: Vec3, velocity: Vec3, heading: Float, lead: Vec3 = Vec3.ZERO, work: () -> Pair<Ballistics.Arc, Boolean>): Pair<Ballistics.Arc, Boolean> {
        val kept = value
        val was = this.aim
        if (kept != null && was != null && now - at <= MAX_AGE && was.distanceToSqr(aim) < AIM_SLACK * AIM_SLACK &&
            velocity.distanceToSqr(this.velocity) < VELOCITY_SLACK * VELOCITY_SLACK && lead.distanceToSqr(this.lead) < VELOCITY_SLACK * VELOCITY_SLACK &&
            Math.abs(net.minecraft.util.Mth.wrapDegrees(heading - this.heading)) < HEADING_SLACK
        ) return kept
        return work().also { this.aim = aim; this.velocity = velocity; this.lead = lead; this.heading = heading; at = now; value = it }
    }

    fun clear() { value = null }

    private companion object {
        const val MAX_AGE = 10L
        const val AIM_SLACK = 0.05
        const val VELOCITY_SLACK = 0.002
        const val HEADING_SLACK = 0.25f
    }
}
