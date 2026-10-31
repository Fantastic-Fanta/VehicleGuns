package dev.vehicleguns.ballistics

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A vehicle's own frame: x ahead, y to its right, z up, for a heading in Minecraft's yaw. Mounts are given in it, and
 * a mount's lie kept in it, so it turns with the vehicle.
 */
object VehicleFrame {
    /** The direction [local] in the world. */
    fun way(heading: Double, local: Vec3): Vec3 {
        val yaw = Math.toRadians(heading)
        val s = sin(yaw)
        val c = cos(yaw)
        return Vec3(-s * local.x - c * local.y, local.z, c * local.x - s * local.y)
    }

    /** The point [local] from [origin] in the world. */
    fun point(origin: Vec3, heading: Double, local: Vec3): Vec3 = origin.add(way(heading, local))

    /** The world direction [world] in the vehicle's frame. */
    fun local(heading: Double, world: Vec3): Vec3 {
        val yaw = Math.toRadians(heading)
        val s = sin(yaw)
        val c = cos(yaw)
        return Vec3(-s * world.x + c * world.z, -c * world.x - s * world.z, world.y)
    }

    /**
     * [from] turned towards [to] (both unit directions) by at most [maxDegrees] along the great circle between them:
     * how a small mount that turns at a steady pace about any axis gets from one lie to the next in a tick.
     */
    fun turnToward(from: Vec3, to: Vec3, maxDegrees: Double): Vec3 {
        val cosine = from.dot(to).coerceIn(-1.0, 1.0)
        val apart = Math.toDegrees(Math.acos(cosine))
        if (apart <= maxDegrees || apart < 1.0e-6) return to
        // Straight back the way it points: any way round will do.
        val across = to.subtract(from.scale(cosine)).let {
            if (it.lengthSqr() > 1.0e-12) it.normalize()
            else from.cross(if (abs(from.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)).normalize()
        }
        val step = Math.toRadians(maxDegrees)
        return from.scale(cos(step)).add(across.scale(sin(step))).normalize()
    }
}
