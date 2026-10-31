package dev.vehicleguns.mixin.client;

import dev.vehicleguns.api.ArmedVehicle;
import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The inventory key of an armed vehicle's gunner opens its magazine (the server calls its openCustomInventoryScreen);
 * anyone else aboard opens their own inventory as ever.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeGunnerMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "isServerControlledInventory", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerMagazine(CallbackInfoReturnable<Boolean> cir) {
        if (minecraft.player != null && minecraft.player.getVehicle() instanceof ArmedVehicle vehicle) {
            cir.setReturnValue(GunnerClient.gunning() == vehicle && vehicle.getMagazine() != null);
        }
    }
}
