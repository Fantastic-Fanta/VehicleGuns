package dev.vehicleguns.mixin.client;

import dev.vehicleguns.api.ArmedVehicle;
import dev.vehicleguns.entity.ShellEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A gunner who sits inside the turret (TurretSpec.hideGunner) is not drawn; nor is a round its first tick, in the muzzle's flash. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherGunnerMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void vehicleguns$hidden(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (entity.getVehicle() instanceof ArmedVehicle vehicle && vehicle.getGunner() == entity && vehicle.getTurretSpec().getHideGunner()) cir.setReturnValue(false);
        if (entity instanceof ShellEntity && entity.tickCount < ShellEntity.SHOWN_AFTER) cir.setReturnValue(false);
    }
}
