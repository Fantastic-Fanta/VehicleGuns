package dev.vehicleguns.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.vehicleguns.ballistics.VehicleFrame
import dev.vehicleguns.demo.GunBoat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.BoatRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * The demo boat, with its turret drawn from plain blocks: a housing that turns, and a barrel that rises in it. A real
 * vehicle draws its own model at [dev.vehicleguns.api.Turret.traverse] and [dev.vehicleguns.api.Turret.elevation].
 */
class GunBoatRenderer(context: EntityRendererProvider.Context) : BoatRenderer(context, false) {
    override fun render(boat: Boat, yaw: Float, partial: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        super.render(boat, yaw, partial, pose, buffers, light)
        if (boat !is GunBoat) return
        val spec = boat.turretSpec
        pose.pushPose()
        // Minecraft's yaw turns +z (the model's ahead) to (-sin, cos): a turn of -yaw about up.
        pose.mulPose(Axis.YP.rotationDegrees(-yaw))
        pose.translate(0.0, spec.pivotUp, spec.pivotForward)
        pose.mulPose(Axis.YP.rotationDegrees(-boat.turret.traverse(partial)))
        block(pose, buffers, light, HOUSING, -0.3, -0.3, -0.3, 0.6, 0.35, 0.6)
        pose.mulPose(Axis.XP.rotationDegrees(-boat.turret.elevation(partial)))
        val bore = spec.barrelRadius * 2
        block(pose, buffers, light, BARREL, -bore / 2, -bore / 2, 0.0, bore, bore, spec.barrel)
        pose.popPose()
        // The machine guns, each a thin barrel from its mount along where it lies.
        for ((i, gun) in spec.machineGuns.withIndex()) {
            val dir = boat.turret.machineGunDirection(i, gun.rest, yaw.toDouble(), partial)
            val at = VehicleFrame.way(yaw.toDouble(), gun.mount)
            pose.pushPose()
            pose.translate(at.x, at.y, at.z)
            pose.mulPose(Axis.YP.rotationDegrees(-Math.toDegrees(Math.atan2(-dir.x, dir.z)).toFloat()))
            pose.mulPose(Axis.XP.rotationDegrees(-Math.toDegrees(Math.asin(dir.y.coerceIn(-1.0, 1.0))).toFloat()))
            block(pose, buffers, light, BARREL, -MG_BORE / 2, -MG_BORE / 2, 0.0, MG_BORE, MG_BORE, gun.barrel)
            pose.popPose()
        }
    }

    /** [state]'s block drawn as a box from ([x], [y], [z]) of [w] by [h] by [d] in the current frame. */
    // NeoForge deprecates this for its model-data overload, which Fabric does not have.
    @Suppress("DEPRECATION")
    private fun block(pose: PoseStack, buffers: MultiBufferSource, light: Int, state: BlockState, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) {
        pose.pushPose()
        pose.translate(x, y, z)
        pose.scale(w.toFloat(), h.toFloat(), d.toFloat())
        Minecraft.getInstance().blockRenderer.renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY)
        pose.popPose()
    }

    private companion object {
        val HOUSING: BlockState = Blocks.POLISHED_ANDESITE.defaultBlockState()
        val BARREL: BlockState = Blocks.IRON_BLOCK.defaultBlockState()
        const val MG_BORE = 0.08
    }
}
