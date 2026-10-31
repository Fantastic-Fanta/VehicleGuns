package dev.vehicleguns.neoforge

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.client.GunBoatRenderer
import dev.vehicleguns.client.MagazineScreen
import dev.vehicleguns.client.VehicleGunsClient
import dev.vehicleguns.network.Net
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.PacketDistributor
import java.util.function.Consumer

/** Client wiring, loaded only on a client. */
internal object VehicleGunsNeoForgeClient {
    fun init(modBus: IEventBus) {
        Net.toServer = { PacketDistributor.sendToServer(it) }
        VehicleGunsClient.init()
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java, Consumer { event ->
            event.registerEntityRenderer(VehicleGuns.SHELL) { ThrownItemRenderer(it, 1.25f, false) }
            event.registerEntityRenderer(VehicleGuns.GUN_BOAT) { GunBoatRenderer(it) }
        })
        modBus.addListener(RegisterKeyMappingsEvent::class.java, Consumer { event -> VehicleGunsClient.KEYS.forEach { event.register(it) } })
        modBus.addListener(RegisterMenuScreensEvent::class.java, Consumer { it.register(VehicleGuns.MAGAZINE_MENU, ::MagazineScreen) })
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java, Consumer { VehicleGunsClient.tick() })
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, Consumer { event ->
            if (event.stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) VehicleGunsClient.renderWorld(event.poseStack, event.camera, event.partialTick.getGameTimeDeltaPartialTick(true))
        })
    }
}
