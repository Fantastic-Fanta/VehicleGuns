package dev.vehicleguns.neoforge

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.network.Clientbound
import dev.vehicleguns.network.Net
import dev.vehicleguns.network.Serverbound
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.neoforged.neoforge.registries.RegisterEvent
import java.util.function.Consumer

@Mod(VehicleGuns.MOD_ID)
class VehicleGunsNeoForge(modBus: IEventBus) {
    init {
        Net.toPlayer = { player, payload -> PacketDistributor.sendToPlayer(player, payload) }
        modBus.addListener(RegisterEvent::class.java, Consumer { event ->
            when (event.registryKey) {
                Registries.ENTITY_TYPE -> VehicleGuns.registerEntityTypes { id, type -> event.register(Registries.ENTITY_TYPE, id) { type } }
                Registries.ITEM -> VehicleGuns.registerItems { id, item -> event.register(Registries.ITEM, id) { item } }
            }
        })
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, Consumer { event ->
            val registrar = event.registrar(PROTOCOL)
            Net.CLIENTBOUND.forEach { clientbound(registrar, it) }
            Net.SERVERBOUND.forEach { serverbound(registrar, it) }
        })
        modBus.addListener(BuildCreativeModeTabContentsEvent::class.java, Consumer { event ->
            if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(VehicleGuns.GUN_BOAT_ITEM)
        })
        if (FMLEnvironment.dist == Dist.CLIENT) VehicleGunsNeoForgeClient.init(modBus)
    }

    private fun <T : CustomPacketPayload> clientbound(registrar: PayloadRegistrar, payload: Clientbound<T>) {
        registrar.playToClient(payload.type, payload.codec) { value, _ -> payload.handle(value) }
    }

    private fun <T : CustomPacketPayload> serverbound(registrar: PayloadRegistrar, payload: Serverbound<T>) {
        registrar.playToServer(payload.type, payload.codec) { value, ctx -> (ctx.player() as? ServerPlayer)?.let { payload.handle(value, it) } }
    }

    private companion object {
        const val PROTOCOL = "1"
    }
}
