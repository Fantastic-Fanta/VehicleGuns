package dev.vehicleguns.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.vehicleguns.entity.SeekerEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/** A homing round drawn from plain blocks along its way: a dark torpedo with a screw, or a white missile with a red nose. */
class SeekerRenderer(context: EntityRendererProvider.Context) : EntityRenderer<SeekerEntity>(context) {
    override fun render(round: SeekerEntity, yaw: Float, partial: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        pose.pushPose()
        pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partial, round.yRotO, round.yRot)))
        pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partial, round.xRotO, round.xRot)))
        val (body, nose) = if (round.torpedo) TORPEDO to TORPEDO_NOSE else MISSILE to MISSILE_NOSE
        // Its body along +z, centred on where it is; a nose ahead and fins or a screw behind.
        block(pose, buffers, light, body, -WIDTH / 2, -WIDTH / 2, -LENGTH / 2, WIDTH, WIDTH, LENGTH)
        block(pose, buffers, light, nose, -NOSE / 2, -NOSE / 2, LENGTH / 2, NOSE, NOSE, NOSE)
        block(pose, buffers, light, nose, -FIN_SPAN / 2, -FIN / 2, -LENGTH / 2, FIN_SPAN, FIN, FIN * 2)
        block(pose, buffers, light, nose, -FIN / 2, -FIN_SPAN / 2, -LENGTH / 2, FIN, FIN_SPAN, FIN * 2)
        pose.popPose()
        super.render(round, yaw, partial, pose, buffers, light)
    }

    // NeoForge deprecates this for its model-data overload, which Fabric does not have.
    @Suppress("DEPRECATION")
    private fun block(pose: PoseStack, buffers: MultiBufferSource, light: Int, state: BlockState, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) {
        pose.pushPose()
        pose.translate(x, y, z)
        pose.scale(w.toFloat(), h.toFloat(), d.toFloat())
        Minecraft.getInstance().blockRenderer.renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY)
        pose.popPose()
    }

    @Suppress("DEPRECATION")
    override fun getTextureLocation(round: SeekerEntity): ResourceLocation = TextureAtlas.LOCATION_BLOCKS

    private companion object {
        val TORPEDO: BlockState = Blocks.POLISHED_DEEPSLATE.defaultBlockState()
        val TORPEDO_NOSE: BlockState = Blocks.COPPER_BLOCK.defaultBlockState()
        val MISSILE: BlockState = Blocks.WHITE_CONCRETE.defaultBlockState()
        val MISSILE_NOSE: BlockState = Blocks.RED_CONCRETE.defaultBlockState()
        const val LENGTH = 0.9
        const val WIDTH = 0.18
        const val NOSE = 0.12
        const val FIN = 0.04
        const val FIN_SPAN = 0.34
    }
}
