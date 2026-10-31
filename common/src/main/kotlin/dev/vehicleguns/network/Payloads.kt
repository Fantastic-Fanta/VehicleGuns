package dev.vehicleguns.network

import dev.vehicleguns.VehicleGuns
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

private fun <T : CustomPacketPayload> type(path: String) = CustomPacketPayload.Type<T>(VehicleGuns.id(path))

/** Client to server: the gunner of [vehicle] (its entity id) pulls the trigger. */
data class GunFirePayload(val vehicle: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<GunFirePayload>("gun_fire")
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, GunFirePayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.vehicle) },
            { buf -> GunFirePayload(buf.readVarInt()) },
        )
    }
}

/** Client to server: the spot the gunner of [vehicle] holds their sight on. Sent as it moves, and at least twice a second. */
data class GunAimPayload(val vehicle: Int, val x: Double, val y: Double, val z: Double) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<GunAimPayload>("gun_aim")
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, GunAimPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.vehicle); buf.writeDouble(p.x); buf.writeDouble(p.y); buf.writeDouble(p.z) },
            { buf -> GunAimPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble()) },
        )
    }
}

/** Client to server: the gunner of [vehicle] asks for a steady platform (down the sight or locked on), or lets it go. */
data class GunSteadyPayload(val vehicle: Int, val steady: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<GunSteadyPayload>("gun_steady")
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, GunSteadyPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.vehicle); buf.writeBoolean(p.steady) },
            { buf -> GunSteadyPayload(buf.readVarInt(), buf.readBoolean()) },
        )
    }
}

/** Server to the gunner who fired it: where their round came down, how many it hurt and how many of those it killed. */
data class GunHitPayload(val x: Double, val y: Double, val z: Double, val hits: Int, val kills: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<GunHitPayload>("gun_hit")
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, GunHitPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeDouble(p.x); buf.writeDouble(p.y); buf.writeDouble(p.z); buf.writeVarInt(p.hits); buf.writeVarInt(p.kills) },
            { buf -> GunHitPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readVarInt()) },
        )
    }
}

/** Server to clients near it: a round burst here, on the water or not. Each client stages the burst itself. */
data class ShellBurstPayload(val x: Double, val y: Double, val z: Double, val water: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<ShellBurstPayload>("shell_burst")
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ShellBurstPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeDouble(p.x); buf.writeDouble(p.y); buf.writeDouble(p.z); buf.writeBoolean(p.water) },
            { buf -> ShellBurstPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean()) },
        )
    }
}
