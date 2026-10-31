package dev.vehicleguns.mixin.client;

import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A gunner keeps the gun's camera: the game's other views (F5) are not taken while they are aboard. */
@Mixin(Options.class)
public abstract class OptionsGunnerMixin {
    @Inject(method = "setCameraType", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerKeepsTheGunCamera(CameraType type, CallbackInfo ci) {
        if (!type.isFirstPerson() && GunnerClient.gunning() != null) ci.cancel();
    }
}
