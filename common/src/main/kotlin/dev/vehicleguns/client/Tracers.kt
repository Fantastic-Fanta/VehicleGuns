package dev.vehicleguns.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * Machine-gun bullets as gun mods draw them: a thin streak of pale yellow light, unlit, as long as a tick's flight and
 * narrowing behind its head, flicked from the muzzle to what it was fired at. A muzzle flash: a star at the barrel's
 * mouth spun at random, there for a tick, drawn see-through and again at half size as light, and a tongue of flame
 * along the barrel turned to the eye, for guns seen from the side. Sparks, or a splash in water, where it lands.
 */
object Tracers {
    /** A bullet in flight: from where to where, how fast (blocks a second), since when (System.nanoTime); and whether it has landed yet. */
    private class Bullet(val from: Vec3, val to: Vec3, val speed: Double, val born: Long, val spin: Float) {
        val length = to.distanceTo(from)
        val way: Vec3 = to.subtract(from).normalize()
        var landed = false
        var flashed = false
        /** How far along it goes into water from the air, if it does (a splash there as it gets that far), and whether it has. */
        var water = Double.NaN
        var splashed = false
    }

    private val bullets = ArrayList<Bullet>()

    /** Fires a bullet from [from] at [to] flying [speed] blocks a tick, with its flash and its report. */
    fun fire(from: Vec3, to: Vec3, speed: Double) {
        val level = Minecraft.getInstance().level ?: return
        val bullet = Bullet(from, to, speed * 20.0, System.nanoTime(), level.random.nextFloat() * Mth.TWO_PI)
        // Fired from the air, where it goes into the water on its way.
        if (level.getFluidState(BlockPos.containing(from)).isEmpty) {
            var d = 0.25
            while (d < bullet.length) {
                val at = from.add(bullet.way.scale(d))
                val pos = BlockPos.containing(at)
                val fluid = level.getFluidState(pos)
                if (!fluid.isEmpty && at.y <= pos.y + fluid.getHeight(level, pos)) { bullet.water = d; break }
                d += 0.25
            }
        }
        bullets.add(bullet)
        level.playLocalSound(from.x, from.y, from.z, SoundEvents.CROSSBOW_SHOOT, SoundSource.NEUTRAL, 0.7f, 1.7f + level.random.nextFloat() * 0.2f, false)
        level.playLocalSound(from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.15f, 2.0f, false)
    }

    /** A bullet going into the water at [at]: a spout of spray thrown back up and a trail of bubbles under. */
    private fun splash(at: Vec3, way: Vec3) {
        val level = Minecraft.getInstance().level ?: return
        val random = level.random
        for (i in 0 until 8) level.addParticle(ParticleTypes.SPLASH, at.x + (random.nextDouble() - 0.5) * 0.3, at.y + 0.1, at.z + (random.nextDouble() - 0.5) * 0.3,
            (random.nextDouble() - 0.5) * 0.1, 0.15 + random.nextDouble() * 0.1, (random.nextDouble() - 0.5) * 0.1)
        for (i in 1..4) level.addParticle(ParticleTypes.BUBBLE, at.x + way.x * i * 0.3, at.y + way.y * i * 0.3, at.z + way.z * i * 0.3, 0.0, 0.02, 0.0)
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.25f, 1.6f + random.nextFloat() * 0.3f, false)
    }

    private fun land(b: Bullet) {
        val level = Minecraft.getInstance().level ?: return
        val at = b.to
        // A bullet that missed and went on into the open air leaves nothing where it is spent.
        val into = BlockPos.containing(at.add(b.way.scale(0.1)))
        val struck = !level.getBlockState(into).getCollisionShape(level, into).isEmpty ||
            level.getEntities(null as Entity?, AABB(at, at).inflate(0.4)) { it is LivingEntity }.isNotEmpty()
        if (!struck && level.getFluidState(BlockPos.containing(at)).isEmpty) return
        if (!level.getFluidState(BlockPos.containing(at)).isEmpty) {
            for (i in 0 until 6) level.addParticle(ParticleTypes.BUBBLE, at.x, at.y, at.z, (level.random.nextDouble() - 0.5) * 0.1, 0.05, (level.random.nextDouble() - 0.5) * 0.1)
            level.addParticle(ParticleTypes.SPLASH, at.x, at.y, at.z, 0.0, 0.1, 0.0)
        } else {
            for (i in 0 until 5) level.addParticle(ParticleTypes.CRIT, at.x, at.y, at.z, -b.way.x * 0.3 + (level.random.nextDouble() - 0.5) * 0.4,
                (level.random.nextDouble()) * 0.3, -b.way.z * 0.3 + (level.random.nextDouble() - 0.5) * 0.4)
            level.addParticle(ParticleTypes.SMOKE, at.x, at.y, at.z, 0.0, 0.02, 0.0)
        }
    }

    /** After the world's entities: each bullet a streak facing the eye, and each muzzle's flash while it lasts. */
    @JvmStatic
    fun render(stack: PoseStack, cameraAt: Camera, partial: Float) {
        if (bullets.isEmpty()) return
        val now = System.nanoTime()
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        val out = buffers.getBuffer(RenderType.debugQuads())
        val camera = cameraAt.position
        val pose = stack.last().pose()
        val gone = ArrayList<Bullet>()
        for (b in bullets) {
            val flown = (now - b.born) / 1.0e9 * b.speed
            if (!b.splashed && !b.water.isNaN() && flown >= b.water) { b.splashed = true; splash(b.from.add(b.way.scale(b.water)), b.way) }
            if (!b.landed && flown >= b.length) { b.landed = true; land(b) }
            val trail = b.speed / 20.0
            if (flown - trail >= b.length) { gone.add(b); continue }
            val headAt = minOf(flown, b.length)
            val tailAt = maxOf(0.0, flown - trail)
            val head = b.from.add(b.way.scale(headAt)).subtract(camera)
            val tail = b.from.add(b.way.scale(tailAt)).subtract(camera)
            // Narrowing and fading from the head as a whole tick's streak would, wherever the muzzle or the target cuts it short.
            val headLeft = 1.0 - (flown - headAt) / trail
            val tailLeft = 1.0 - (flown - tailAt) / trail
            streak(out, pose, head, tail, b.way, WIDTH * headLeft, WIDTH * tailLeft, ALPHA * headLeft.toFloat(), ALPHA * tailLeft.toFloat())
        }
        buffers.endBatch(RenderType.debugQuads())
        // Muzzle flashes, for the tick after each shot.
        for (layer in 0..1) {
            val type = if (layer == 0) RenderType.entityTranslucent(FLASH) else RenderType.eyes(FLASH)
            val flash = buffers.getBuffer(type)
            for (b in bullets) {
                val age = (now - b.born) / 1.0e9
                // Drawn at least once, however slow the frames.
                if (age >= FLASH_SECONDS && b.flashed) continue
                val grown = minOf(1.0, maxOf(0.3, age / (FLASH_SECONDS / 2))) * (if (layer == 0) 1.0 else 0.5)
                flash(flash, pose, b.from.subtract(camera), b.way, b.spin, FLASH_SIZE * grown, FLASH_LENGTH * grown, layer == 1)
                if (layer == 1) b.flashed = true
            }
            buffers.endBatch(type)
        }
        bullets.removeAll(gone)
    }

    /**
     * One muzzle flash at [mouth] (from the eye), firing along [way]: the star facing down the barrel, turned [spin]
     * about it, [size] across, then the flame tongue [length] out along the barrel, turned about it to face the eye.
     */
    private fun flash(out: VertexConsumer, pose: Matrix4f, mouth: Vec3, way: Vec3, spin: Float, size: Double, length: Double,
                      bothSides: Boolean) {
        val up = if (Math.abs(way.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val a = way.cross(up).normalize()
        val b = way.cross(a)
        val c = Mth.cos(spin).toDouble() * size / 2
        val d = Mth.sin(spin).toDouble() * size / 2
        val s = a.scale(c).add(b.scale(d))
        val t = a.scale(-d).add(b.scale(c))
        val star = mouth.add(way.scale(0.05))
        quad(out, pose, bothSides, star.subtract(s).subtract(t), 0f, 1f, star.add(s).subtract(t), 0.5f, 1f,
            star.add(s).add(t), 0.5f, 0f, star.subtract(s).add(t), 0f, 0f)
        // The tongue: from the mouth out along the barrel, its width across the line of sight.
        val side = way.cross(mouth).let { if (it.lengthSqr() < 1.0e-9) a else it.normalize() }.scale(size * 0.35)
        val tip = mouth.add(way.scale(length))
        quad(out, pose, bothSides, mouth.subtract(side), 0.5f, 1f, mouth.add(side), 0.5f, 0f, tip.add(side), 1f, 0f, tip.subtract(side), 1f, 1f)
    }

    /** A quad's four corners, and again wound the other way when its render type culls backs (the glow's does). */
    private fun quad(out: VertexConsumer, pose: Matrix4f, bothSides: Boolean,
                     p0: Vec3, u0: Float, v0: Float, p1: Vec3, u1: Float, v1: Float, p2: Vec3, u2: Float, v2: Float, p3: Vec3, u3: Float, v3: Float) {
        corner(out, pose, p0, u0, v0); corner(out, pose, p1, u1, v1); corner(out, pose, p2, u2, v2); corner(out, pose, p3, u3, v3)
        if (bothSides) { corner(out, pose, p3, u3, v3); corner(out, pose, p2, u2, v2); corner(out, pose, p1, u1, v1); corner(out, pose, p0, u0, v0) }
    }

    private fun corner(out: VertexConsumer, pose: Matrix4f, at: Vec3, u: Float, v: Float) {
        out.addVertex(pose, at.x.toFloat(), at.y.toFloat(), at.z.toFloat()).setColor(1f, 1f, 1f, 1f).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(0f, 1f, 0f)
    }

    /** A ribbon from [head] to [tail] (both from the eye), turned about its length to face it, [width] either side at its head and [tailWidth] at its tail. */
    private fun streak(out: VertexConsumer, pose: Matrix4f, head: Vec3, tail: Vec3, way: Vec3, width: Double,
                       tailWidth: Double, headAlpha: Float, tailAlpha: Float) {
        val side = way.cross(head).let { if (it.lengthSqr() < 1.0e-9) Vec3(0.0, 1.0, 0.0) else it.normalize() }
        val across = side.scale(width)
        val behind = side.scale(tailWidth)
        out.addVertex(pose, (head.x - across.x).toFloat(), (head.y - across.y).toFloat(), (head.z - across.z).toFloat()).setColor(R, G, B, headAlpha)
        out.addVertex(pose, (head.x + across.x).toFloat(), (head.y + across.y).toFloat(), (head.z + across.z).toFloat()).setColor(R, G, B, headAlpha)
        out.addVertex(pose, (tail.x + behind.x).toFloat(), (tail.y + behind.y).toFloat(), (tail.z + behind.z).toFloat()).setColor(R, G, B, tailAlpha)
        out.addVertex(pose, (tail.x - behind.x).toFloat(), (tail.y - behind.y).toFloat(), (tail.z - behind.z).toFloat()).setColor(R, G, B, tailAlpha)
    }

    /** Pale warm yellow, drawn unlit, so as bright at night and underground as by day. */
    private const val R = 1.0f
    private const val G = 0.82f
    private const val B = 0.54f
    /** A streak's half width at its head, blocks, and how bright its head is, 0 to 1. Its length is a tick's flight. */
    private const val WIDTH = 0.035
    private const val ALPHA = 0.6f
    /** The muzzle flash: its sprite (the star, then the tongue), how long it shows (a tick), the star's width and the tongue's length, blocks. */
    private val FLASH: ResourceLocation = ResourceLocation.fromNamespaceAndPath("vehicleguns", "textures/effect/muzzle_flash.png")
    private const val FLASH_SECONDS = 0.05
    private const val FLASH_SIZE = 0.7
    private const val FLASH_LENGTH = 0.8
    private const val FULL_BRIGHT = 0xF000F0
}
