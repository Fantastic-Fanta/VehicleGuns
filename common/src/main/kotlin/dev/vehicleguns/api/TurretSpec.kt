package dev.vehicleguns.api

import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/**
 * How a vehicle's gun is built and handled. Distances are blocks, angles degrees, times ticks.
 *
 * The barrel turns about a pivot [pivotForward] ahead of the vehicle's origin and [pivotUp] over it, reaches [barrel]
 * out to its muzzle and is [barrelRadius] thick. It may be laid no lower than [lowest] and no higher than [highest]
 * anywhere round. Under a gunner it handles as [gunnerMount], on its own as [guardMount]. It fires [round]s, guards on
 * its own as [guard] says (null: an unmanned gun does nothing), and its gunner looks out from [camera].
 */
data class TurretSpec(
    val pivotForward: Double = 0.0,
    val pivotUp: Double = 1.0,
    val barrel: Double = 1.5,
    val barrelRadius: Double = 0.15,
    val lowest: Double = -5.0,
    val highest: Double = 85.0,
    val gunnerMount: MountHandling = MountHandling(traverseTop = 6.0, traverseAccel = 1.0, elevateTop = 3.0, elevateAccel = 0.6),
    val guardMount: MountHandling = MountHandling(traverseTop = 9.0, traverseAccel = 1.2, elevateTop = 6.0, elevateAccel = 1.0),
    val round: RoundSpec = RoundSpec(),
    val guard: GuardSpec? = GuardSpec(),
    val camera: CameraSpec = CameraSpec(),
    /** The gunner sits inside the turret: they are not drawn while aboard. */
    val hideGunner: Boolean = false,
)

/**
 * A mount's pace: the turret's fastest swing and the barrel's fastest rise, degrees a tick, and how much of that each
 * may gain or lose in a tick.
 */
data class MountHandling(val traverseTop: Double, val traverseAccel: Double, val elevateTop: Double, val elevateAccel: Double)

/**
 * The round the gun fires: its muzzle [speed] and [gravity] (blocks a tick, and a tick squared), how long it flies
 * before it is spent, and how long the gun takes to load. A gunner's lay is the flat arc no lower than [directLowest],
 * or the lob, no lower than [lobLowest], when the flat arc would meet something short of the spot; never above
 * [highest]. A gunner's sight ranges spots out to [reach]. It bursts with [blastPower] (4 is TNT) and sets what it
 * hurts alight for [fireSeconds]. It is drawn as [item].
 */
data class RoundSpec(
    val speed: Double = 1.9,
    val gravity: Double = 0.045,
    val lifeTicks: Int = 200,
    val reloadTicks: Int = 30,
    val reach: Double = 96.0,
    val directLowest: Double = -8.0,
    val lobLowest: Double = 30.0,
    val highest: Double = 84.0,
    val blastPower: Float = 4.0f,
    val fireSeconds: Float = 5f,
    val item: Item = Items.FIRE_CHARGE,
)

/**
 * The gun guarding on its own, with no gunner: it lays on hostile mobs within [range] ([TargetRules.guardMayHit]),
 * looks for a better one every [searchTicks], and fires a guard's round that spares every block and everyone but
 * hostile mobs. With nothing to shoot for [watchAfterTicks] it keeps watch, sweeping [watchArc] degrees either side of
 * ahead once each way in [watchTicks], the barrel raised [watchElevation].
 */
data class GuardSpec(
    val range: Double = 24.0,
    val searchTicks: Int = 10,
    val watchAfterTicks: Int = 60,
    val watchArc: Double = 45.0,
    val watchTicks: Double = 240.0,
    val watchElevation: Double = 8.0,
)

/**
 * The gunner's camera: [back] blocks behind a point [up] over the gun's pivot, along the way they look, and [rise]
 * higher again, so looking about swings it round the vehicle and the gun and its round are in front of them.
 */
data class CameraSpec(val back: Double = 11.0, val up: Double = 1.5, val rise: Double = 2.5)
