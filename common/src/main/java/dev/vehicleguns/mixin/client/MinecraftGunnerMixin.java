package dev.vehicleguns.mixin.client;

import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A gunner's use and attack keys work the gun alone (GunnerClient): never the item in hand nor what their own eye is on. */
@Mixin(Minecraft.class)
public abstract class MinecraftGunnerMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerUses(CallbackInfo ci) {
        if (GunnerClient.gunning() != null) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerAttacks(CallbackInfoReturnable<Boolean> cir) {
        if (GunnerClient.gunning() != null) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerKeepsAttacking(boolean leftClick, CallbackInfo ci) {
        if (GunnerClient.gunning() != null) ci.cancel();
    }
}
