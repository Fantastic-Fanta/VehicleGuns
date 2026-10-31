package dev.vehicleguns.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
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
    }

    /** [state]'s block drawn as a box from ([x], [y], [z]) of [w] by [h] by [d] in the current frame. */
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
    }
}
