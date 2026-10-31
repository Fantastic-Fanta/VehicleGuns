package dev.vehicleguns.fabric

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.client.GunBoatRenderer
import dev.vehicleguns.client.GunnerClient
import dev.vehicleguns.client.GunnerMarks
import dev.vehicleguns.client.ShellBursts
import dev.vehicleguns.network.Clientbound
import dev.vehicleguns.network.Net
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

object VehicleGunsFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        Net.toServer = { ClientPlayNetworking.send(it) }
        Net.CLIENTBOUND.forEach { clientbound(it) }
        EntityRendererRegistry.register(VehicleGuns.SHELL) { ThrownItemRenderer(it, 1.25f, false) }
        EntityRendererRegistry.register(VehicleGuns.GUN_BOAT) { GunBoatRenderer(it) }
        KeyBindingHelper.registerKeyBinding(GunnerClient.LOCK_KEY)
        ClientTickEvents.END_CLIENT_TICK.register {
            GunnerClient.tick()
            ShellBursts.tick()
        }
        WorldRenderEvents.AFTER_ENTITIES.register { context ->
            val stack = context.matrixStack() ?: return@register
            GunnerMarks.render(stack, context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(true))
        }
    }

    private fun <T : CustomPacketPayload> clientbound(payload: Clientbound<T>) {
        ClientPlayNetworking.registerGlobalReceiver(payload.type) { value, _ -> payload.handle(value) }
    }
}
