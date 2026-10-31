package dev.vehicleguns.client

import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.entity
import dev.vehicleguns.gun.Aiming
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * The gunner's camera and the power of their sight. The camera floats over the vehicle's bob rather than jolting with
 * it. Down the sight it magnifies so the spot it is on looks [ZOOM_NEAR] blocks off, between [ZOOM_LEAST] and
 * [ZOOM_MOST] times, trimmed by the mouse wheel, easing as the spot's distance changes.
 */
internal object Sight {
    private var bobY = Double.NaN
    private var bobOff = 0.0
    private var bobAt = 0L
    private var eye: Vec3? = null

    private var trim = DEFAULT_TRIM
    private var zoomNow = 1f
    private var zoomAt = 0L
    private var zoomDistance = Double.NaN

    fun frame(vehicle: ArmedVehicle, rider: Player, partial: Float): Vec3 {
        val now = System.nanoTime()
        val dt = ((now - bobAt) / 1.0e9).coerceIn(0.0, 0.1)
        bobAt = now
        val y = vehicle.entity.getPosition(partial).y
        bobY = if (bobY.isNaN() || Math.abs(bobY - y) > 2.0) y else bobY + (y - bobY) * (1.0 - Math.exp(-dt / BOB_EASE))
        bobOff = bobY - y
        return camera(vehicle, rider.getViewYRot(partial), rider.getViewXRot(partial), partial).also { eye = it }
    }

    fun camera(vehicle: ArmedVehicle, yaw: Float, pitch: Float, partial: Float): Vec3 =
        Aiming.sight(vehicle, yaw, pitch, partial).add(0.0, bobOff, 0.0)

    /** Steps the wheel's trim [notches] (up for more power), with a soft click higher the further in. */
    fun trim(notches: Int) {
        val step = (trim + notches).coerceIn(0, TRIMS.size - 1)
        if (step == trim) return
        trim = step
        Minecraft.getInstance().player?.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.15f, 1.2f + 0.15f * step)
    }

    /** The share of the field of view shown now, 1 for none, easing to where [zoomed] has it. */
    fun zoomShare(zoomed: Boolean): Float {
        val now = System.nanoTime()
        val dt = ((now - zoomAt) / 1.0e9).coerceIn(0.0, 0.1)
        zoomAt = now
        val seen = GunnerClient.aim?.let { spot -> eye?.distanceTo(spot) }?.takeIf { it > 0.5 }
        if (seen != null) zoomDistance = if (zoomDistance.isNaN()) seen
            else Math.exp(Mth.lerp(1.0 - Math.exp(-dt / ZOOM_DISTANCE_EASE), Math.log(zoomDistance), Math.log(seen)))
        val distance = if (zoomDistance.isNaN()) ZOOM_NEAR * 3.0 else zoomDistance
        val power = (distance / ZOOM_NEAR * TRIMS[trim]).coerceIn(ZOOM_LEAST, ZOOM_MOST)
        val want = if (zoomed) (1.0 / power).toFloat() else 1f
        zoomNow += ((want - zoomNow) * (1.0 - Math.exp(-dt / ZOOM_EASE))).toFloat()
        if (Math.abs(want - zoomNow) < 1e-4f) zoomNow = want
        return zoomNow
    }

    fun reset() {
        bobY = Double.NaN
        bobOff = 0.0
        eye = null
        zoomNow = 1f
        zoomDistance = Double.NaN
    }

    private const val BOB_EASE = 0.25
    private const val ZOOM_EASE = 0.06
    private const val ZOOM_NEAR = 12.0
    private const val ZOOM_LEAST = 1.7
    private const val ZOOM_MOST = 8.0
    private const val ZOOM_DISTANCE_EASE = 0.35
    private val TRIMS = doubleArrayOf(0.5, 0.7, 1.0, 1.4, 2.0)
    private const val DEFAULT_TRIM = 2
}
