package dev.vehicleguns.mixin.client;

import dev.vehicleguns.client.GunnerClient;
import dev.vehicleguns.client.GunnerHud;
import dev.vehicleguns.client.ShellBursts;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The gunner sees the gun's sight (GunnerHud) in place of the crosshair and the hotbar; anyone near a burst sees its flash. */
@Mixin(Gui.class)
public abstract class GuiGunnerMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerCrosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (GunnerClient.ownsView()) ci.cancel();
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true, require = 0)
    private void vehicleguns$gunnerHotbar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (GunnerClient.ownsView()) ci.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void vehicleguns$gunnerHud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        ShellBursts.renderFlash(graphics);
        GunnerHud.render(graphics);
    }
}
