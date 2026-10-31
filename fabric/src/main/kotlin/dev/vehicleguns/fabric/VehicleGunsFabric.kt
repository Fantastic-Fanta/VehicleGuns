package dev.vehicleguns.fabric

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.network.Net
import dev.vehicleguns.network.Serverbound
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.item.CreativeModeTabs

object VehicleGunsFabric : ModInitializer {
    override fun onInitialize() {
        VehicleGuns.registerEntityTypes { id, type -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type) }
        VehicleGuns.registerItems { id, item -> Registry.register(BuiltInRegistries.ITEM, id, item) }
        Net.toPlayer = { player, payload -> ServerPlayNetworking.send(player, payload) }
        Net.CLIENTBOUND.forEach { PayloadTypeRegistry.playS2C().register(it.type, it.codec) }
        Net.SERVERBOUND.forEach { serverbound(it) }
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register { it.accept(VehicleGuns.GUN_BOAT_ITEM) }
    }

    private fun <T : CustomPacketPayload> serverbound(payload: Serverbound<T>) {
        PayloadTypeRegistry.playC2S().register(payload.type, payload.codec)
        ServerPlayNetworking.registerGlobalReceiver(payload.type) { value, ctx -> payload.handle(value, ctx.player()) }
    }
}
