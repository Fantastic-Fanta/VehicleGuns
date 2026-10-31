package dev.vehicleguns.fabric

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.client.GunBoatRenderer
import dev.vehicleguns.client.MagazineScreen
import dev.vehicleguns.client.SeekerRenderer
import dev.vehicleguns.client.VehicleGunsClient
import dev.vehicleguns.network.Clientbound
import dev.vehicleguns.network.Net
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

object VehicleGunsFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        Net.toServer = { ClientPlayNetworking.send(it) }
        Net.CLIENTBOUND.forEach { clientbound(it) }
        VehicleGunsClient.init()
        MenuScreens.register(VehicleGuns.MAGAZINE_MENU, ::MagazineScreen)
        EntityRendererRegistry.register(VehicleGuns.SHELL) { ThrownItemRenderer(it, 1.25f, false) }
        EntityRendererRegistry.register(VehicleGuns.GUN_BOAT) { GunBoatRenderer(it) }
        EntityRendererRegistry.register(VehicleGuns.SEEKER) { SeekerRenderer(it) }
        VehicleGunsClient.KEYS.forEach { KeyBindingHelper.registerKeyBinding(it) }
        ClientTickEvents.END_CLIENT_TICK.register { VehicleGunsClient.tick() }
        WorldRenderEvents.AFTER_ENTITIES.register { context ->
            val stack = context.matrixStack() ?: return@register
            VehicleGunsClient.renderWorld(stack, context.camera(), context.tickCounter().getGameTimeDeltaPartialTick(true))
        }
    }

    private fun <T : CustomPacketPayload> clientbound(payload: Clientbound<T>) {
        ClientPlayNetworking.registerGlobalReceiver(payload.type) { value, _ -> payload.handle(value) }
    }
}
