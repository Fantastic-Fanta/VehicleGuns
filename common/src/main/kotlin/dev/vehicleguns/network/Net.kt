package dev.vehicleguns.network

import dev.vehicleguns.gun.Turrets
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/** A payload the server sends; [handle] runs on the client's main thread. */
class Clientbound<T : CustomPacketPayload>(
    val type: CustomPacketPayload.Type<T>,
    val codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
    val handle: (T) -> Unit,
)

/** A payload a client sends; [handle] runs on the server's main thread with its sender. */
class Serverbound<T : CustomPacketPayload>(
    val type: CustomPacketPayload.Type<T>,
    val codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
    val handle: (T, ServerPlayer) -> Unit,
)

/**
 * The mod's payloads and how to send them. Each loader registers [CLIENTBOUND] and [SERVERBOUND] its own way and
 * sets [toPlayer] (and, on the client, [toServer]).
 */
object Net {
    lateinit var toPlayer: (ServerPlayer, CustomPacketPayload) -> Unit
    var toServer: (CustomPacketPayload) -> Unit = { error("Not on a client") }

    // Client classes are named only inside the handlers, so a dedicated server never loads them.
    val CLIENTBOUND: List<Clientbound<*>> = listOf(
        Clientbound(GunHitPayload.TYPE, GunHitPayload.CODEC) { dev.vehicleguns.client.GunnerClient.hit(it) },
        Clientbound(ShellBurstPayload.TYPE, ShellBurstPayload.CODEC) { dev.vehicleguns.client.ShellBursts.start(it) },
        Clientbound(TorpedoBurstPayload.TYPE, TorpedoBurstPayload.CODEC) { dev.vehicleguns.client.TorpedoFx.burst(it) },
    )

    val SERVERBOUND: List<Serverbound<*>> = listOf(
        Serverbound(GunFirePayload.TYPE, GunFirePayload.CODEC) { p, player -> Turrets.fireByGunner(player, p.vehicle) },
        Serverbound(GunAimPayload.TYPE, GunAimPayload.CODEC) { p, player -> Turrets.aimByGunner(player, p.vehicle, Vec3(p.x, p.y, p.z)) },
        Serverbound(GunMachinePayload.TYPE, GunMachinePayload.CODEC) { p, player -> Turrets.machineGunByGunner(player, p.vehicle, Vec3(p.x, p.y, p.z)) },
        Serverbound(GunSeekerPayload.TYPE, GunSeekerPayload.CODEC) { p, player -> Turrets.seekersByGunner(player, p.vehicle, p.mark) },
        Serverbound(GunSteadyPayload.TYPE, GunSteadyPayload.CODEC) { p, player -> Turrets.steadyByGunner(player, p.vehicle, p.steady) },
    )
}
