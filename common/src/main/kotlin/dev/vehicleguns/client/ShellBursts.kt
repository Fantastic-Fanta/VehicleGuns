package dev.vehicleguns.client

import dev.vehicleguns.GunSettings
import dev.vehicleguns.network.ShellBurstPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A round bursting, staged on each client over a few seconds rather than a single puff: a flash, a fireball swelling
 * and rolling up, a shockwave racing out over the ground or the water, embers thrown on arcs, then a column of water
 * and a ring of foam or a plume of what it came down on, and smoke that hangs. Heard late by its distance. Near enough,
 * the view shakes and the screen flashes warm; the gunner whose round it was feels a small thump however far off.
 */
object ShellBursts {
    private class Burst(val at: Vec3, val water: Boolean, val ground: BlockState?, val startNanos: Long, val density: Double) {
        var done = -1
    }

    private val bursts = ArrayList<Burst>()
    private var shakeNanos = Long.MIN_VALUE / 4
    private var shake = 0.0
    private var flashNanos = Long.MIN_VALUE / 4
    private var flash = 0.0

    fun start(payload: ShellBurstPayload) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val at = Vec3(payload.x, payload.y, payload.z)
        val ground = if (payload.water) null else level.getBlockState(BlockPos.containing(at.x, at.y - 0.2, at.z)).takeIf { !it.isAir }
        val distance = mc.gameRenderer.mainCamera.position.distanceTo(at)
        // Thinned out far off, where a crowd of particles is a smudge and only costs; the shape is kept.
        val density = (1.3 - distance / 70.0).coerceIn(0.35, 1.0) * GunSettings.burstParticles
        bursts.add(Burst(at, payload.water, ground, System.nanoTime(), density))
        sounds(mc, at, payload.water, ground, distance)
        val now = System.nanoTime()
        val shakeHere = (1.0 - distance / SHAKE_REACH).coerceIn(0.0, 1.0).pow(2) * SHAKE_MOST
        if (shakeHere > shakeNow(now)) { shake = shakeHere; shakeNanos = now }
        val flashHere = (1.0 - distance / FLASH_REACH).coerceIn(0.0, 1.0).pow(1.5) * FLASH_MOST
        if (flashHere > flashNow(now)) { flash = flashHere; flashNanos = now }
    }

    /** Another blast felt here: the view shaken up to [most] degrees, falling off to nothing [reach] blocks from [at]. */
    fun feel(at: Vec3, most: Double, reach: Double) {
        val distance = Minecraft.getInstance().gameRenderer.mainCamera.position.distanceTo(at)
        val now = System.nanoTime()
        val here = (1.0 - distance / reach).coerceIn(0.0, 1.0).pow(2) * most
        if (here > shakeNow(now)) { shake = here; shakeNanos = now }
    }

    /** The gunner's own round has landed: a small thump in the view, however far off. */
    fun thump() {
        val now = System.nanoTime()
        if (THUMP > shakeNow(now)) { shake = THUMP; shakeNanos = now }
    }

    private fun shakeNow(now: Long) = shake * exp(-((now - shakeNanos) / 1.0e9) / SHAKE_SETTLE)
    private fun flashNow(now: Long) = flash * exp(-((now - flashNanos) / 1.0e9) / FLASH_FADE)

    /** Heard late by its distance: a boom, a crack close by, a far rumble; a splash and the column falling back, or the ground broken. */
    // NeoForge deprecates BlockState.soundType for its level-aware overload, which Fabric does not have.
    @Suppress("DEPRECATION")
    private fun sounds(mc: Minecraft, at: Vec3, water: Boolean, ground: BlockState?, distance: Double) {
        val random = RandomSource.create()
        val late = (distance / SOUND_SPEED * 20.0).toInt()
        fun put(sound: SoundEvent, reach: Double, pitch: Float, after: Int = 0) {
            if (distance > reach) return
            mc.soundManager.playDelayed(SimpleSoundInstance(sound, SoundSource.NEUTRAL, (reach / 16.0).toFloat(),
                pitch + (random.nextFloat() - 0.5f) * 0.08f, random, at.x, at.y, at.z), late + after)
        }
        put(SoundEvents.GENERIC_EXPLODE.value(), 96.0, 0.5f)
        put(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 40.0, 0.55f)
        if (distance > 24.0) put(SoundEvents.LIGHTNING_BOLT_THUNDER, 140.0, 1.5f)
        if (water) {
            put(SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 64.0, 0.55f)
            put(SoundEvents.GENERIC_SPLASH, 48.0, 0.5f, 22)
            put(SoundEvents.PLAYER_SPLASH, 32.0, 0.7f, 26)
        } else if (ground != null) {
            put(ground.soundType.breakSound, 48.0, 0.55f)
            put(ground.soundType.fallSound, 32.0, 0.6f, 10)
            put(ground.soundType.stepSound, 24.0, 0.7f, 16)
        }
    }

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

    /** What the burst [b] throws out in its [k]th tick. */
    private fun stage(level: Level, b: Burst, k: Int) {
        if (b.density <= 0.0) return
        val r = level.random
        val at = b.at
        fun n(count: Int) = Math.max(1, Math.round(count * b.density).toInt())
        fun put(type: ParticleOptions, x: Double, y: Double, z: Double, vx: Double = 0.0, vy: Double = 0.0, vz: Double = 0.0) =
            level.addParticle(type, true, x, y, z, vx, vy, vz)
        fun around(radius: Double): Vec3 {
            val u = r.nextDouble() * 2 - 1
            val a = r.nextDouble() * 2 * Math.PI
            val s = Math.sqrt(1 - u * u) * radius * Math.cbrt(r.nextDouble())
            return Vec3(cos(a) * s, Math.abs(u) * radius * Math.cbrt(r.nextDouble()), sin(a) * s)
        }
        fun ring(count: Int, speed: Double, lift: Double, type: ParticleOptions) {
            for (i in 0 until count) {
                val a = i * 2 * Math.PI / count + r.nextDouble() * 0.1
                put(type, at.x + cos(a) * 0.6, at.y + lift, at.z + sin(a) * 0.6, cos(a) * speed, 0.02, sin(a) * speed)
            }
        }
        val bits = b.ground?.let { BlockParticleOption(ParticleTypes.BLOCK, it) }
        if (k == 0) {
            // The flash, the burst's heart, embers on arcs, and the shockwave over the water or the ground.
            put(ParticleTypes.FLASH, at.x, at.y + 0.5, at.z)
            put(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z)
            repeat(n(10)) { val p = around(1.6); put(ParticleTypes.EXPLOSION, at.x + p.x, at.y + p.y, at.z + p.z) }
            repeat(n(if (b.water) 8 else 16)) { put(ParticleTypes.LAVA, at.x, at.y + 0.3, at.z) }
            repeat(n(14)) {
                val a = r.nextDouble() * 2 * Math.PI
                val out = 0.18 + r.nextDouble() * 0.25
                put(ParticleTypes.FLAME, at.x, at.y + 0.4, at.z, cos(a) * out, 0.25 + r.nextDouble() * 0.35, sin(a) * out)
            }
            ring(n(56), 0.62, 0.15, ParticleTypes.CLOUD)
            if (b.water) ring(n(40), 0.5, 0.05, ParticleTypes.SPLASH) else bits?.let { ring(n(40), 0.45, 0.1, it) }
        }
        if (k in 1..6) {
            // The fireball swelling and rolling up, steam over the water.
            val radius = 0.6 + k * 0.45
            repeat(n(7)) {
                val p = around(radius)
                put(if (b.water && r.nextBoolean()) ParticleTypes.CLOUD else ParticleTypes.LARGE_SMOKE, at.x + p.x, at.y + p.y + k * 0.3, at.z + p.z, 0.0, 0.08, 0.0)
            }
            if (k <= 3) repeat(n(if (b.water) 2 else 5)) { val p = around(radius * 0.7); put(ParticleTypes.FLAME, at.x + p.x, at.y + p.y + k * 0.25, at.z + p.z, 0.0, 0.06, 0.0) }
            if (k == 2) ring(n(40), 0.4, 0.2, ParticleTypes.POOF)
        }
        if (k in 1..9) {
            if (b.water) {
                // A column of water thrown up.
                repeat(n(20)) {
                    val a = r.nextDouble() * 2 * Math.PI
                    val s = r.nextDouble() * 1.1
                    put(ParticleTypes.CLOUD, at.x + cos(a) * s, at.y + 0.2, at.z + sin(a) * s, cos(a) * 0.06, 0.5 + r.nextDouble() * 0.6, sin(a) * 0.06)
                }
                repeat(n(10)) {
                    val a = r.nextDouble() * 2 * Math.PI
                    put(ParticleTypes.SPLASH, at.x + cos(a) * 0.8, at.y + 0.2, at.z + sin(a) * 0.8, cos(a) * 0.15, 0.6 + r.nextDouble() * 0.5, sin(a) * 0.15)
                }
                repeat(n(4)) { put(ParticleTypes.FALLING_WATER, at.x + r.nextGaussian(), at.y + 2 + r.nextDouble() * 8, at.z + r.nextGaussian()) }
            } else if (bits != null) {
                // A plume of what it came down on.
                repeat(n(14)) {
                    val a = r.nextDouble() * 2 * Math.PI
                    val out = 0.08 + r.nextDouble() * 0.25
                    put(bits, at.x, at.y + 0.3, at.z, cos(a) * out, 0.4 + r.nextDouble() * 0.55, sin(a) * out)
                }
            }
        }
        if (b.water) {
            // Foam spreading, bubbles breaking, and a second splash as the column falls back.
            if (k in 3..24 && k % 3 == 0) ring(n(28), 0.12, 0.05, ParticleTypes.CLOUD)
            if (k in 4..40) repeat(n(3)) { put(ParticleTypes.BUBBLE_POP, at.x + r.nextGaussian() * 2.5, at.y + 0.05, at.z + r.nextGaussian() * 2.5) }
            if (k in 22..26) {
                ring(n(36), 0.28, 0.1, ParticleTypes.SPLASH)
                repeat(n(10)) { put(ParticleTypes.SPLASH, at.x + r.nextGaussian() * 1.5, at.y + 0.2, at.z + r.nextGaussian() * 1.5, 0.0, 0.35, 0.0) }
            }
        }
        // A column of smoke rising and spreading at its head, hanging a few seconds.
        if (k in 4..60 && k % 2 == 0) {
            put(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x + r.nextGaussian() * 0.4, at.y + 0.5, at.z + r.nextGaussian() * 0.4, 0.0, 0.06, 0.0)
            if (k in 10..40) repeat(n(2)) {
                val a = r.nextDouble() * 2 * Math.PI
                put(ParticleTypes.LARGE_SMOKE, at.x + cos(a) * 0.8, at.y + 5 + r.nextDouble() * 2, at.z + sin(a) * 0.8, cos(a) * 0.06, 0.02, sin(a) * 0.06)
            }
        }
    }

    /** The shake, degrees of (pitch, yaw), on whoever is near a burst. */
    @JvmStatic
    fun shake(): FloatArray {
        if (!GunSettings.screenShake) return NONE
        val now = System.nanoTime()
        val amp = shakeNow(now)
        if (amp < 0.01) return NONE
        val t = (now - shakeNanos) / 1.0e9
        return floatArrayOf((amp * sin(t * 61.0)).toFloat(), (amp * 0.7 * sin(t * 47.0 + 1.3)).toFloat())
    }

    /** The flash over the screen, brief and warm, on whoever is near a burst. */
    @JvmStatic
    fun renderFlash(graphics: GuiGraphics) {
        if (!GunSettings.burstFlash) return
        val a = flashNow(System.nanoTime())
        if (a < 0.01) return
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), ((a * 255).toInt().coerceIn(0, 255) shl 24) or WARM)
    }

    private val NONE = floatArrayOf(0f, 0f)
    private const val WARM = 0xFFD8A0
    private const val SHAKE_REACH = 45.0
    private const val SHAKE_MOST = 2.4
    private const val SHAKE_SETTLE = 0.35
    private const val THUMP = 0.45
    private const val FLASH_REACH = 40.0
    private const val FLASH_MOST = 0.55
    private const val FLASH_FADE = 0.12
    /** Sound's pace, blocks a second. */
    private const val SOUND_SPEED = 343.0
    /** A burst plays out over this many ticks. */
    private const val LASTS = 60
}
