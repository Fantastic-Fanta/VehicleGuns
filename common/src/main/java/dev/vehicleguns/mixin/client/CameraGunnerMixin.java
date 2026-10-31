package dev.vehicleguns.mixin.client;

import dev.vehicleguns.api.ArmedVehicle;
import dev.vehicleguns.client.GunnerClient;
import dev.vehicleguns.client.ShellBursts;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The gunner looks out from the gun's camera behind and over their vehicle (GunnerClient.cameraFrame), seeing themselves
 * aboard; the view kicks as the gun fires and shakes near a burst.
 */
@Mixin(Camera.class)
public abstract class CameraGunnerMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(Vec3 position);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow public abstract float getYRot();

    @Shadow public abstract float getXRot();

    @Inject(method = "setup", at = @At("TAIL"))
    private void vehicleguns$gunnerCamera(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        ArmedVehicle vehicle = GunnerClient.gunning();
        if (vehicle != null && !detached && entity == Minecraft.getInstance().player) {
            setPosition(GunnerClient.cameraFrame(vehicle, (Player) entity, partialTick));
            this.detached = true;
        }
        float[] kick = GunnerClient.kick();
        float[] shake = ShellBursts.shake();
        float pitch = kick[0] + shake[0];
        float yaw = kick[1] + shake[1];
        if (pitch != 0f || yaw != 0f) setRotation(getYRot() + yaw, getXRot() + pitch);
    }
}
