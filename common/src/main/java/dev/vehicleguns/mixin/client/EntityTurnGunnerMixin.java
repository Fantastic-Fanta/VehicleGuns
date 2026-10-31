package dev.vehicleguns.mixin.client;

import dev.vehicleguns.client.GunnerClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Down the sight the mouse turns the view less, so the aim keeps its feel on the screen (GunnerClient.mouseShare). */
@Mixin(Entity.class)
public abstract class EntityTurnGunnerMixin {
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double vehicleguns$gunnerYaw(double yaw) {
        return (Object) this == Minecraft.getInstance().player ? yaw * GunnerClient.mouseShare() : yaw;
    }

    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double vehicleguns$gunnerPitch(double pitch) {
        return (Object) this == Minecraft.getInstance().player ? pitch * GunnerClient.mouseShare() : pitch;
    }
}
