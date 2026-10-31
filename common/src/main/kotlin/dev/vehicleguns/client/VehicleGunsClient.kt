package dev.vehicleguns.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.vehicleguns.gun.Turrets
import net.minecraft.client.Camera
import net.minecraft.client.KeyMapping

/** What each loader's client setup calls: once at start, every client tick, and after the world's entities are drawn. */
object VehicleGunsClient {
    val KEYS: List<KeyMapping> get() = listOf(GunnerClient.LOCK_KEY, GunnerClient.MACHINE_GUN_KEY)

    fun init() {
        Turrets.clientTick = MachineGunFx::tick
    }

    fun tick() {
        GunnerClient.tick()
        ShellBursts.tick()
    }

    fun renderWorld(stack: PoseStack, camera: Camera, partial: Float) {
        GunnerMarks.render(stack, camera, partial)
        Tracers.render(stack, camera, partial)
    }
}
