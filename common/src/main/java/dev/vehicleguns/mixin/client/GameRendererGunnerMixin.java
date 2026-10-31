package dev.vehicleguns.mixin.client;

import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The gunner's field of view narrows down the sight (GunnerClient.fov), and their own eye picks nothing: the block
 * outline and what attack would hit belong to a view they no longer have.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererGunnerMixin {
    @Shadow @Final Minecraft minecraft;

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void vehicleguns$gunnerFov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (useFovSetting) cir.setReturnValue(GunnerClient.fov(cir.getReturnValueD()));
    }

    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void vehicleguns$gunnerPicksNothing(float partialTick, CallbackInfo ci) {
        if (GunnerClient.gunning() == null) return;
        Vec3 at = minecraft.player != null ? minecraft.player.getEyePosition(partialTick) : Vec3.ZERO;
        minecraft.hitResult = BlockHitResult.miss(at, Direction.UP, BlockPos.containing(at));
        minecraft.crosshairPickEntity = null;
    }
}
