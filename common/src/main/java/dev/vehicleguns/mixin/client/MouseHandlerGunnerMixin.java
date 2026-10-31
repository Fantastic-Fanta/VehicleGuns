package dev.vehicleguns.mixin.client;

import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While gunning, the mouse buttons and wheel are the gun's (GunnerClient): a click taken there never reaches the game. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerGunnerMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (window == minecraft.getWindow().getWindow() && GunnerClient.onMouseButton(button, action)) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$gunnerWheel(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (window == minecraft.getWindow().getWindow() && yOffset != 0.0 && GunnerClient.onMouseScroll(yOffset)) ci.cancel();
    }
}
