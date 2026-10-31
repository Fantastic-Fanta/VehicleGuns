package dev.vehicleguns.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.vehicleguns.gun.Aiming
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the gunner sees in the world: one ring on the spot the gun is laid on, in the lay's colour, open by as much as
 * the gun is still off it and whole once it is laid; the arc a round would fly, down the sight; brackets round a
 * locked target; and for a round in the air a wide circle on where it comes down, closing in as it falls.
 */
object GunnerMarks {
    @JvmStatic
    fun render(stack: PoseStack, camera: Camera, partial: Float) {
        val vehicle = GunnerClient.gunning() ?: return
        val where = GunnerClient.lands ?: return
        val ticked = GunnerClient.aim ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val pivot = Aiming.pivot(vehicle)
        // Worked out each frame from the view as it is, so the ring stays under the crosshair as the mouse sweeps; locked,
        // it is the spot as led.
        val spot = if (GunnerClient.locked != null) ticked else Aiming.aimedFrom(level, vehicle, player, camera.position, player.getViewVector(partial)).spot
        val buffers = mc.renderBuffers().bufferSource()
        val out = buffers.getBuffer(RenderType.lines())
        val eye = camera.position
        val pose = stack.last()
        val alpha = if (GunnerClient.loading(vehicle)) 120 else 235
        val (r, g, b) = GunnerHud.rgb(GunnerHud.stateColour())
        val size = maxOf(RING, spot.distanceTo(eye) * RING_PER_BLOCK)
        val off = Math.hypot(where.x - spot.x, where.z - spot.z)
        val range = Math.hypot(spot.x - pivot.x, spot.z - pivot.z)
        val closed = if (GunnerClient.laid || !GunnerClient.reaches) 1.0 else (1.0 - off / maxOf(CLOSING_FROM, range * CLOSING_SHARE)).coerceIn(0.1, 0.92)
        ring(out, pose, spot.subtract(eye), size, r, g, b, alpha, closed)
        ring(out, pose, spot.subtract(eye), size * 0.2, r, g, b, alpha)
        // The arc, only down the sight where it is worth reading, stopping short of the ring.
        if (GunnerClient.zoomed) {
            val path = GunnerClient.path
            for (i in 1 until path.size) {
                if (path[i].distanceTo(where) < size * 4.0) break
                line(out, pose, path[i - 1].subtract(eye), path[i].subtract(eye), r, g, b, alpha / 2)
            }
        }
        GunnerClient.locked?.let { target ->
            val (lr, lg, lb) = GunnerHud.rgb(LOCK)
            brackets(out, pose, camera, target, eye, lr, lg, lb)
            if (spot.distanceTo(target.position()) > 0.5) line(out, pose, target.position().subtract(eye), spot.subtract(eye), lr, lg, lb, 160)
        }
        buffers.endBatch(RenderType.lines())
        // The round in the air: a circle facing the camera, seen through what is in the way, closing on where it comes down.
        GunnerClient.flying?.let { at ->
            val share = (((System.nanoTime() - GunnerClient.firedAtNanos) / 1.0e9) / maxOf(0.05, GunnerClient.flyingPath.size / 20.0)).coerceIn(0.0, 1.0)
            val small = maxOf(RING, at.distanceTo(eye) * RING_PER_BLOCK)
            val wide = maxOf(small * 4.0, INCOMING_WIDE)
            val over = buffers.getBuffer(SEE_THROUGH)
            facing(over, pose, at.add(0.0, 0.1, 0.0).subtract(eye), wide + (small - wide) * share, left(camera), up(camera), 120, 200, 255, 230)
            buffers.endBatch(SEE_THROUGH)
        }
    }

    private fun left(camera: Camera) = camera.leftVector.let { Vec3(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
    private fun up(camera: Camera) = camera.upVector.let { Vec3(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }

    private fun brackets(out: VertexConsumer, pose: PoseStack.Pose, camera: Camera, target: Entity, eye: Vec3, r: Int, g: Int, b: Int) {
        val box = target.boundingBox
        val centre = box.center.subtract(eye)
        val half = maxOf(box.xsize, box.ysize) * 0.5 + 0.4
        val side = left(camera)
        val up = up(camera)
        for ((sx, sy) in CORNERS) {
            val corner = centre.add(side.scale(sx * half)).add(up.scale(sy * half))
            line(out, pose, corner, corner.subtract(side.scale(sx * half * 0.4)), r, g, b, 240)
            line(out, pose, corner, corner.subtract(up.scale(sy * half * 0.4)), r, g, b, 240)
        }
    }

    /** A ring lying flat at [at]; with [share] under 1, only that much of it. */
    private fun ring(out: VertexConsumer, pose: PoseStack.Pose, at: Vec3, radius: Double, r: Int, g: Int, b: Int, a: Int, share: Double = 1.0) {
        val y = at.y + 0.06
        val drawn = Math.max(1, Math.round(SEGMENTS * share).toInt())
        for (i in 0 until drawn) {
            val a0 = -Math.PI / 2 + i * 2 * Math.PI / SEGMENTS
            val a1 = -Math.PI / 2 + (i + 1) * 2 * Math.PI / SEGMENTS
            line(out, pose, Vec3(at.x + radius * cos(a0), y, at.z + radius * sin(a0)), Vec3(at.x + radius * cos(a1), y, at.z + radius * sin(a1)), r, g, b, a)
        }
    }

    /** A circle round [at] facing the camera, across [side] and [up]. */
    private fun facing(out: VertexConsumer, pose: PoseStack.Pose, at: Vec3, radius: Double, side: Vec3, up: Vec3, r: Int, g: Int, b: Int, a: Int) {
        fun point(t: Double) = at.add(side.scale(radius * cos(t))).add(up.scale(radius * sin(t)))
        for (i in 0 until SEGMENTS) line(out, pose, point(i * 2 * Math.PI / SEGMENTS), point((i + 1) * 2 * Math.PI / SEGMENTS), r, g, b, a)
    }

    private fun line(out: VertexConsumer, pose: PoseStack.Pose, a: Vec3, z: Vec3, r: Int, g: Int, b: Int, alpha: Int) {
        val n = z.subtract(a).normalize()
        out.addVertex(pose.pose(), a.x.toFloat(), a.y.toFloat(), a.z.toFloat()).setColor(r, g, b, alpha).setNormal(pose, n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
        out.addVertex(pose.pose(), z.x.toFloat(), z.y.toFloat(), z.z.toFloat()).setColor(r, g, b, alpha).setNormal(pose, n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
    }

    /** The game's lines, drawn over whatever is in the way. */
    private val SEE_THROUGH: RenderType = object : RenderType("vehicleguns_see_through", DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES, 1536, false, false,
        { lines().setupRenderState(); RenderSystem.disableDepthTest() },
        { RenderSystem.enableDepthTest(); lines().clearRenderState() }) {}

    private val CORNERS = listOf(-1.0 to -1.0, 1.0 to -1.0, -1.0 to 1.0, 1.0 to 1.0)
    /** A lock's colour: an orange red, apart from the lay's own. */
    private const val LOCK = 0xFFFF7A3C.toInt()
    private const val RING = 1.0
    /** A ring is at least this share of its distance across, so it keeps its size on the screen. */
    private const val RING_PER_BLOCK = 0.03
    private const val SEGMENTS = 28
    /** The incoming round's circle starts at least this wide, blocks. */
    private const val INCOMING_WIDE = 6.0
    /** The ring is open by the share the gun's landing is off the spot of this many blocks, or this share of the range if more. */
    private const val CLOSING_FROM = 4.0
    private const val CLOSING_SHARE = 0.25
}
