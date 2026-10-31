package dev.vehicleguns.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The gunner's camera is far from their hands: none are drawn in front of it. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererGunnerMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$noHands(float partialTick, PoseStack pose, MultiBufferSource.BufferSource buffers, LocalPlayer player, int light, CallbackInfo ci) {
        if (GunnerClient.gunning() != null) ci.cancel();
    }
}
