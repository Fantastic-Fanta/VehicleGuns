package dev.vehicleguns.client

import dev.vehicleguns.GunSettings
import dev.vehicleguns.entity.SeekerEntity
import dev.vehicleguns.network.TorpedoBurstPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Torpedoes as each client hears and sees them ([SeekerEntity]): the whirr of the screw following each one as it runs,
 * spinning up as it gathers way; and the burst under water, played out over a second. A flash, a ring of bubbles thrown
 * out and a cloud of them boiling up, a deep thump and a small shake for whoever is near; then, where the water's face is
 * near over it, the face breaks: a column of white water thrown up, foam spreading round its foot, and the column
 * falling back in a second splash.
 */
object TorpedoFx {
    /**
     * Its screw's whirr, following [round] while it lasts (SeekerEntity.clientStarted): only within [RUN_HEARD] blocks,
     * and no more than [RUNS] at once.
     */
    @JvmStatic
    fun start(round: SeekerEntity) {
        if (!round.torpedo) return
        val mc = Minecraft.getInstance()
        running.removeIf { it.isStopped }
        if (running.size >= RUNS || mc.gameRenderer.mainCamera.position.distanceTo(round.position()) > RUN_HEARD) return
        val run = Run(round)
        running.add(run)
        mc.soundManager.play(run)
    }

    private val running = ArrayList<Run>()

    private class Run(val round: SeekerEntity) : AbstractTickableSoundInstance(SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        init {
            looping = true
            delay = 0
            volume = 0.05f
            pitch = RUN_PITCH
            relative = false
            x = round.x; y = round.y; z = round.z
        }

        override fun tick() {
            if (round.isRemoved) { stop(); return }
            x = round.x; y = round.y; z = round.z
            // Spinning up as it gathers way out of its tube; quieter out of the water.
            val way = (round.deltaMovement.length() / RUN_TOP).coerceIn(0.0, 1.0).toFloat()
            volume = Mth.lerp(0.3f, volume, (if (round.isInWater) RUN_LOUD else RUN_LOUD * 0.3f) * (0.4f + 0.6f * way))
            pitch = RUN_PITCH + RUN_PITCH_GAIN * way
        }
    }

    /** A torpedo's track on the water's face at [face] over it, a tick: the fishing wake along its way from [back], bubbles breaking, a little spray. */
    @JvmStatic
    fun track(round: SeekerEntity, back: Vec3, face: Double) {
        val level = round.level() as? ClientLevel ?: return
        val random = level.random
        val way = round.position().subtract(back)
        if (round.tickCount % 2 == 0) level.addParticle(ParticleTypes.SPLASH, round.x, face + 0.1, round.z, 0.0, 0.1, 0.0)
        for (k in 0 until 3) {
            val at = back.add(way.scale(k / 3.0))
            level.addParticle(ParticleTypes.FISHING, at.x + (random.nextDouble() - 0.5) * 0.4, face + 0.05, at.z + (random.nextDouble() - 0.5) * 0.4, way.x * 0.15, 0.0, way.z * 0.15)
        }
        if (round.tickCount % 3 == 0) level.addParticle(ParticleTypes.BUBBLE_POP, round.x, face + 0.05, round.z, 0.0, 0.0, 0.0)
    }

    private class Burst(val at: Vec3, val face: Double?, val startNanos: Long, val near: Double) {
        var done = -1
    }

    private val bursts = ArrayList<Burst>()

    // NeoForge deprecates BlockState.soundType for its level-aware overload, which Fabric does not have.
    @Suppress("DEPRECATION")
    fun burst(payload: TorpedoBurstPayload) {
        val mc = Minecraft.getInstance()
        val at = Vec3(payload.x, payload.y, payload.z)
        val distance = mc.gameRenderer.mainCamera.position.distanceTo(at)
        val near = (1.3 - distance / 60.0).coerceIn(0.35, 1.0) * GunSettings.burstParticles
        bursts.add(Burst(at, payload.face.takeIf { !it.isNaN() }, System.nanoTime(), near))
        val level = mc.level ?: return
        // The bed or bank it burst against stirred up in a cloud of it, and heard.
        payload.bed.takeIf { it >= 0 }?.let { Block.stateById(it) }?.takeIf { !it.isAir }?.let { bed ->
            val bits = BlockParticleOption(ParticleTypes.BLOCK, bed)
            repeat((40 * near).toInt().coerceAtLeast(10)) {
                val v = Vec3(level.random.nextGaussian(), level.random.nextGaussian(), level.random.nextGaussian()).normalize().scale(0.1 + level.random.nextDouble() * 0.3)
                level.addParticle(bits, true, at.x, at.y, at.z, v.x, v.y + 0.2, v.z)
            }
            level.playLocalSound(at.x, at.y, at.z, bed.soundType.breakSound, SoundSource.BLOCKS, 1.3f, bed.soundType.pitch * 0.6f, false)
        }
        // The thump: deep and muffled under the water, felt as a small shake close by.
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 2.2f, 0.42f + level.random.nextFloat() * 0.06f, false)
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundSource.NEUTRAL, 1.6f, 0.6f, false)
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, SoundSource.NEUTRAL, 1.4f, 0.8f, false)
        ShellBursts.feel(at, SHAKE, SHAKE_REACH)
    }

    /** Each client tick: each burst's stages that are due played. */
    @JvmStatic
    fun tick() {
        val level = Minecraft.getInstance().level ?: run { bursts.clear(); return }
        val now = System.nanoTime()
        val it = bursts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val due = ((now - b.startNanos) / 50_000_000L).toInt()
            while (b.done < due && b.done < LASTS) { b.done++; stage(level, b, b.done) }
            if (b.done >= LASTS) it.remove()
        }
    }

    /** What the burst [b] does in its [k]th tick. */
    private fun stage(level: ClientLevel, b: Burst, k: Int) {
        val r = level.random
        val at = b.at
        fun n(count: Int) = Math.max(1, Math.round(count * b.near).toInt())
        fun put(type: ParticleOptions, x: Double, y: Double, z: Double, vx: Double = 0.0, vy: Double = 0.0, vz: Double = 0.0) =
            level.addParticle(type, true, x, y, z, vx, vy, vz)
        // Under the water: the game's own explosion puffs, a ring of bubbles thrown out flat, a ball of them every way,
        // and bubbles boiling up after.
        if (k == 0) {
            put(ParticleTypes.FLASH, at.x, at.y, at.z)
            repeat(3) { put(ParticleTypes.EXPLOSION, at.x + r.nextGaussian() * 0.4, at.y + r.nextGaussian() * 0.4, at.z + r.nextGaussian() * 0.4) }
            repeat(n(48)) {
                val a = it * 2 * Math.PI / 48 + r.nextDouble() * 0.1
                put(ParticleTypes.BUBBLE, at.x, at.y, at.z, cos(a) * 0.9, (r.nextDouble() - 0.5) * 0.1, sin(a) * 0.9)
            }
            repeat(n(40)) {
                val v = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(0.3 + r.nextDouble() * 0.5)
                put(ParticleTypes.BUBBLE, at.x, at.y, at.z, v.x, v.y, v.z)
            }
        }
        if (k in 0..12) repeat(n(6)) {
            put(ParticleTypes.BUBBLE_COLUMN_UP, at.x + r.nextGaussian() * 0.7, at.y + r.nextDouble() * 0.5, at.z + r.nextGaussian() * 0.7, 0.0, 0.3, 0.0)
        }
        val face = b.face ?: return
        // The face breaks as the shock reaches it: sooner the shallower.
        val breaks = 2 + ((face - at.y) / FACE_PACE).toInt()
        if (k == breaks) {
            level.playLocalSound(at.x, face, at.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.NEUTRAL, 1.8f, 0.7f, false)
            level.playLocalSound(at.x, face, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.4f, 0.6f, false)
            ring(level, at.x, face, at.z, n(32), 0.4, 0.25, ParticleTypes.SPLASH)
        }
        if (k in breaks until breaks + 6) {
            // A column of white water thrown up out of the face, five or six blocks.
            repeat(n(10)) {
                val a = r.nextDouble() * 2 * Math.PI
                val s = r.nextDouble() * 0.7
                put(ParticleTypes.CLOUD, at.x + cos(a) * s, face + 0.1, at.z + sin(a) * s, cos(a) * 0.04, 0.45 + r.nextDouble() * 0.45, sin(a) * 0.04)
            }
            repeat(n(8)) {
                val a = r.nextDouble() * 2 * Math.PI
                put(ParticleTypes.SPLASH, at.x + cos(a) * 0.5, face + 0.1, at.z + sin(a) * 0.5, cos(a) * 0.12, 0.6 + r.nextDouble() * 0.5, sin(a) * 0.12)
            }
            repeat(n(2)) { put(ParticleTypes.FALLING_WATER, at.x + r.nextGaussian() * 0.6, face + 1.5 + r.nextDouble() * 4, at.z + r.nextGaussian() * 0.6) }
        }
        // Foam spreading on the face, bubbles breaking, and the column falling back in a second splash.
        if (k in breaks + 2..breaks + 16 && (k - breaks) % 3 == 0) ring(level, at.x, face, at.z, n(22), 0.1, 0.02, ParticleTypes.CLOUD)
        if (k in breaks + 2..breaks + 30) repeat(n(2)) { put(ParticleTypes.BUBBLE_POP, at.x + r.nextGaussian() * 1.6, face + 0.05, at.z + r.nextGaussian() * 1.6) }
        if (k == breaks + 14) {
            ring(level, at.x, face, at.z, n(26), 0.22, 0.12, ParticleTypes.SPLASH)
            level.playLocalSound(at.x, face, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.2f, 1.0f, false)
        }
    }

    private fun ring(level: ClientLevel, x: Double, y: Double, z: Double, count: Int, speed: Double, lift: Double, type: ParticleOptions) {
        for (i in 0 until count) {
            val a = i * 2 * Math.PI / count
            level.addParticle(type, true, x + cos(a) * 0.4, y + 0.1, z + sin(a) * 0.4, cos(a) * speed, lift, sin(a) * speed)
        }
    }

    /** The whirrs playing at once at most, and from how far one is heard, blocks. */
    private const val RUNS = 4
    private const val RUN_HEARD = 48.0
    /** The whirr: how loud, how low, and how much higher at full speed ([RUN_TOP] blocks a tick). */
    private const val RUN_LOUD = 0.55f
    private const val RUN_PITCH = 0.5f
    private const val RUN_PITCH_GAIN = 0.35f
    private const val RUN_TOP = 1.4
    /** A burst shakes the view this many degrees close by, falling off to nothing this many blocks off. */
    private const val SHAKE = 0.9
    private const val SHAKE_REACH = 24.0
    /** The shock rises through the water this many blocks a tick, breaking the face when it gets there. */
    private const val FACE_PACE = 3.0
    /** A burst plays out over this many ticks. */
    private const val LASTS = 50
}
