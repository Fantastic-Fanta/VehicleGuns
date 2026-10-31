package dev.vehicleguns.client

import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.entity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.Mth

/**
 * The gunner's sight on the screen, with no words: four ticks round an open middle in the lay's colour (white laid,
 * amber still laying, violet laying a lob, red out of reach), growing in from their ends as the gun loads; a blink
 * while a pull waits; a red flash for a pull that cannot fire; a hit marker, red for a kill; the rounds left; a warm
 * flash round the edges as it fires; a compass with the turret's bearing while anything turns; and a mark at the
 * screen's edge for a round in the air out of view.
 */
object GunnerHud {
    @JvmStatic
    fun render(graphics: GuiGraphics) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || !GunnerClient.ownsView()) return
        val vehicle = GunnerClient.gunning() ?: return
        val cx = graphics.guiWidth() / 2
        val cy = graphics.guiHeight() / 2
        val now = System.nanoTime()
        // A round in the air is done with a second after it should have come down.
        if (GunnerClient.flying != null && GunnerClient.flyingPath.size / 20.0 - (now - GunnerClient.firedAtNanos) / 1.0e9 < -1.0) {
            GunnerClient.flying = null
            GunnerClient.flyingPath = emptyList()
        }
        muzzleFlash(graphics, now)
        reticle(graphics, vehicle, cx, cy, now)
        hitMarker(graphics, cx, cy, now)
        heatBar(graphics, vehicle, cx, cy)
        compass(graphics, vehicle, cx, now)
        roundOffScreen(graphics, now)
    }

    internal fun stateColour(): Int = when {
        !GunnerClient.reaches -> RED
        GunnerClient.laid -> WHITE
        GunnerClient.lob -> VIOLET
        else -> AMBER
    }

    internal fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun muzzleFlash(graphics: GuiGraphics, now: Long) {
        val fired = (now - GunnerClient.firedAtNanos) / 1.0e9
        if (fired !in 0.0..FLASH_SECONDS) return
        // Round the edges only, fading inwards: the middle, where they look, stays clear.
        val strength = 1.0 - fired / FLASH_SECONDS
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        for (band in 0 until 6) {
            val c = ((strength * 46 * (6 - band) / 6).toInt().coerceIn(0, 255) shl 24) or 0xFFA040
            val d = band * 6
            graphics.fill(d, d, w - d, d + 6, c)
            graphics.fill(d, h - d - 6, w - d, h - d, c)
            graphics.fill(d, d + 6, d + 6, h - d - 6, c)
            graphics.fill(w - d - 6, d + 6, w - d, h - d - 6, c)
        }
    }

    private fun reticle(graphics: GuiGraphics, vehicle: ArmedVehicle, cx: Int, cy: Int, now: Long) {
        val refused = (now - GunnerClient.flashNanos) / 1.0e9 < REFUSED_SECONDS
        val blink = vehicle.turret.pullWaiting && (now / 125_000_000L) % 2 == 0L
        val colour = if (refused) RED else stateColour()
        val tick = if (blink) (colour and 0x00FFFFFF) or (0x80 shl 24) else colour
        val reload = vehicle.turretSpec.round.reloadTicks / 20.0
        val load = if (GunnerClient.loading(vehicle)) 1.0 - (GunnerClient.loadingLeft(vehicle) / reload).coerceIn(0.0, 1.0) else 1.0
        val inner = 12 - Math.round(2 + 5 * load).toInt()
        val outer = 12
        graphics.fill(cx - outer, cy, cx - inner + 1, cy + 1, tick)
        graphics.fill(cx + inner, cy, cx + outer + 1, cy + 1, tick)
        graphics.fill(cx, cy - outer, cx + 1, cy - inner + 1, tick)
        graphics.fill(cx, cy + inner, cx + 1, cy + outer + 1, tick)
        val rounds = vehicle.turret.roundsShown
        if (rounds >= 0) {
            val shade = when { rounds == 0 -> RED; rounds <= LOW_ROUNDS -> AMBER; else -> 0xC0E0E0E0.toInt() }
            graphics.drawString(Minecraft.getInstance().font, rounds.toString(), cx + 9, cy + 9, shade, true)
        }
    }

    private fun hitMarker(graphics: GuiGraphics, cx: Int, cy: Int, now: Long) {
        val since = (now - GunnerClient.hitNanos) / 1.0e9
        val lasts = if (GunnerClient.killed) HIT_SECONDS * 1.6 else HIT_SECONDS
        if (since >= lasts) return
        val c = (((1.0 - since / lasts) * 255).toInt().coerceIn(0, 255) shl 24) or (if (GunnerClient.killed) 0xFF3C3C else 0xFFFFFF)
        val grow = if (since < 0.08) 2 else 0
        for (k in 7..11 + grow) {
            graphics.fill(cx - k, cy - k, cx - k + 1, cy - k + 1, c)
            graphics.fill(cx + k, cy - k, cx + k + 1, cy - k + 1, c)
            graphics.fill(cx - k, cy + k, cx - k + 1, cy + k + 1, c)
            graphics.fill(cx + k, cy + k, cx + k + 1, cy + k + 1, c)
        }
    }

    /** The machine guns' heat under the reticle, while it is not full or their key is held: amber while they cool. */
    private fun heatBar(graphics: GuiGraphics, vehicle: ArmedVehicle, cx: Int, cy: Int) {
        if (vehicle.turretSpec.machineGuns.isEmpty() || vehicle.turretSpec.heat == null) return
        val heat = vehicle.turret.heat
        if (heat >= 1f && !GunnerClient.machineGunsHeld) return
        val y = cy + HEAT_BAR_Y
        val half = HEAT_BAR_WIDTH / 2
        graphics.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, 0x80000000.toInt())
        val filled = Math.round(HEAT_BAR_WIDTH * heat.coerceIn(0f, 1f))
        graphics.fill(cx - half, y, cx - half + filled, y + 1, if (vehicle.turret.overheated) AMBER else WHITE)
    }

    /** A compass tape along the top while the view or the turret turns: north as 0, the turret's bearing marked under it. */
    private fun compass(graphics: GuiGraphics, vehicle: ArmedVehicle, cx: Int, now: Long) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val idle = (now - maxOf(GunnerClient.movedNanos, GunnerClient.swungNanos)) / 1.0e9
        val shown = (1.0 - (idle - COMPASS_HOLD) / COMPASS_FADE).coerceIn(0.0, 1.0)
        if (shown <= 0.0) return
        fun fade(argb: Int): Int = (((argb ushr 24) * shown).toInt() shl 24) or (argb and 0x00FFFFFF)
        val width = 160
        val top = 4
        // Minecraft's yaw 0 looks south: the tape reads north as 0.
        val look = Mth.wrapDegrees(player.yRot + 180f)
        val perDegree = width / 120.0
        graphics.fill(cx - width / 2, top, cx + width / 2, top + 14, fade(0x40000000))
        for (deg in -180..165 step 15) {
            val off = Mth.wrapDegrees(deg - look)
            if (Math.abs(off) > 58) continue
            val x = cx + Math.round(off * perDegree).toInt()
            val point = deg % 45 == 0
            graphics.fill(x, top + (if (point) 1 else 3), x + 1, top + 5, fade(0xFFB0B0B0.toInt()))
            if (point) {
                val letter = POINTS[Math.floorMod(deg, 360) / 45]
                graphics.drawCenteredString(mc.font, letter, x + 1, top + 5, fade(if (letter.length == 1) 0xFFE0E0E0.toInt() else 0xFFA0A0A0.toInt()))
            }
        }
        graphics.fill(cx, top, cx + 1, top + 14, fade(WHITE))
        val gun = Mth.wrapDegrees(vehicle.entity.yRot + vehicle.turret.traverse + 180f)
        val off = Mth.wrapDegrees(gun - look)
        val gx = cx + Math.round(off.coerceIn(-58f, 58f) * perDegree).toInt()
        val mark = fade(if (Math.abs(off) < 1.5f) WHITE else AMBER)
        graphics.fill(gx, top + 14, gx + 1, top + 15, mark)
        graphics.fill(gx - 1, top + 15, gx + 2, top + 16, mark)
        graphics.fill(gx - 2, top + 16, gx + 3, top + 17, mark)
    }

    /** A round in the air off the screen (it climbs out of view within a moment): a mark at the edge in its direction. */
    private fun roundOffScreen(graphics: GuiGraphics, now: Long) {
        if (GunnerClient.flying == null) return
        val mc = Minecraft.getInstance()
        val at = GunnerClient.flyingPath.getOrNull(((now - GunnerClient.firedAtNanos) / 1.0e9 * 20.0).toInt()) ?: return
        val camera = mc.gameRenderer.mainCamera
        val d = at.subtract(camera.position)
        val dyaw = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-d.x, d.z)) - camera.yRot)
        val dpitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))) - camera.xRot
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        val vHalf = mc.options.fov().get() / 2.0
        val hHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vHalf)) * w / h))
        if (Math.abs(dyaw) < hHalf * 0.95 && Math.abs(dpitch) < vHalf * 0.95) return
        val x = (w / 2 + dyaw / hHalf * w / 2).toInt().coerceIn(10, w - 10)
        val y = (h / 2 + dpitch / vHalf * h / 2).toInt().coerceIn(22, h - 10)
        for (k in 0..3) {
            graphics.fill(x - 3 + k, y - k, x + 4 - k, y - k + 1, INCOMING)
            graphics.fill(x - 3 + k, y + k, x + 4 - k, y + k + 1, INCOMING)
        }
    }

    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val AMBER = 0xFFFFBE3C.toInt()
    private const val VIOLET = 0xFFB48CFF.toInt()
    private const val RED = 0xFFFF4646.toInt()
    private const val INCOMING = 0xFF78C8FF.toInt()
    private const val LOW_ROUNDS = 3
    private const val FLASH_SECONDS = 0.12
    private const val HIT_SECONDS = 0.6
    private const val REFUSED_SECONDS = 0.35
    private const val HEAT_BAR_Y = 18
    private const val HEAT_BAR_WIDTH = 25
    private const val COMPASS_HOLD = 1.5
    private const val COMPASS_FADE = 0.5
}
