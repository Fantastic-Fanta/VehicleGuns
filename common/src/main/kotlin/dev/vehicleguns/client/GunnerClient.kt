package dev.vehicleguns.client

import com.mojang.blaze3d.platform.InputConstants
import dev.vehicleguns.GunSettings
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.SeekerSpec
import dev.vehicleguns.api.entity
import dev.vehicleguns.ballistics.Ballistics
import dev.vehicleguns.entity.SeekerEntity
import dev.vehicleguns.gun.Aiming
import dev.vehicleguns.gun.LayCache
import dev.vehicleguns.gun.Turrets
import dev.vehicleguns.network.GunAimPayload
import dev.vehicleguns.network.GunFirePayload
import dev.vehicleguns.network.GunHitPayload
import dev.vehicleguns.network.GunMachinePayload
import dev.vehicleguns.network.GunSeekerPayload
import dev.vehicleguns.network.GunSteadyPayload
import dev.vehicleguns.network.Net
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

/**
 * The local player's hand on the gun of the vehicle they are gunner of. They aim by looking: the server lays the gun
 * on the spot under their crosshair (as a mortar, flat or lobbed), and the attack button fires it. They see from a
 * camera behind and over the vehicle ([Aiming.sight]); the use button, held, looks down the sight and asks for a steady
 * platform; the lock key ([LOCK_KEY]) locks on the creature under the crosshair and leads it while they look about.
 *
 * A pull while the gun still lays or is nearly loaded is kept and fires the moment it can; held, the trigger keeps
 * firing; a pull that cannot fire clicks dry. What they see is drawn by [GunnerMarks] (in the world) and [GunnerHud]
 * (on the screen) from the state kept here.
 */
object GunnerClient {
    @JvmField
    val LOCK_KEY = KeyMapping("key.vehicleguns.lock", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.vehicleguns")

    /** Held, the machine guns turn to the crosshair and fire full auto ([machineGunKey]). */
    @JvmField
    val MACHINE_GUN_KEY = KeyMapping("key.vehicleguns.machine_guns", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.vehicleguns")

    /** Whether the machine guns' key is held now, for the sight. */
    internal var machineGunsHeld = false

    /** Held, the seekers launch at what the gunner marks ([seekerKey]). */
    @JvmField
    val SEEKER_KEY = KeyMapping("key.vehicleguns.seekers", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.vehicleguns")

    /** What the seekers would go at now, bracketed on the screen while their key is held; and whether it is. */
    internal var marked: Entity? = null
    internal var seekersHeld = false

    /** The vehicle the local player is gunner of, or null. */
    @JvmStatic
    fun gunning(): ArmedVehicle? {
        val player = Minecraft.getInstance().player ?: return null
        return (player.vehicle as? ArmedVehicle)?.takeIf { it.gunner === player }
    }

    // What the sight shows, refreshed each tick.
    internal var aim: Vec3? = null
    internal var lands: Vec3? = null
    /** The flight a round fired now would take, muzzle to landing, a point a tick. */
    internal var path: List<Vec3> = emptyList()
    internal var laid = false
    internal var reaches = true
    internal var near = false
    internal var lob = false
    internal var zoomed = false

    /** The round in the air as the client saw it fired: where it was to come down and its arc; null once it has. */
    internal var flying: Vec3? = null
    internal var flyingPath: List<Vec3> = emptyList()

    /** The creature locked on, and its way along the ground, blocks a tick, smoothed. */
    internal var locked: Entity? = null
    private var lockedWay = Vec3.ZERO
    private var lockedWas: Vec3? = null
    private var lockHeld = false

    // Timings, System.nanoTime.
    internal var firedAtNanos = NEVER
    internal var flashNanos = NEVER
    internal var hitNanos = NEVER
    internal var movedNanos = NEVER
    internal var swungNanos = NEVER
    internal var killed = false
    private var laidSoundNanos = NEVER
    private var pulledNanos = NEVER

    private var boarded: ArmedVehicle? = null
    /** The view the gunner had before boarding, given back as they get off. */
    private var viewBefore: CameraType? = null
    private var shotsSeen = -1
    private var pulledShots = -1
    private var wasKept = false
    private var wasLoading = false
    private var roundsWas = -1
    /** The vehicle's way, blocks a tick, smoothed: the client's positions come in steps. */
    private var way = Vec3.ZERO
    private var wasAt: Vec3? = null
    private var turretWas = 0f
    private var turnSoundTicks = 0
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var steadySent = false
    private var aimSent: Vec3? = null
    private var aimSentTick = 0L
    private val layCache = LayCache()

    // The buttons as the gunner has them bound: a mouse button's clicks are taken here and never reach the game; a key is
    // read as the game reads it, and does nothing else meanwhile (MinecraftGunnerMixin).
    private var useButton = GLFW.GLFW_MOUSE_BUTTON_RIGHT
    private var useMouse = false
    private var triggerButton = GLFW.GLFW_MOUSE_BUTTON_LEFT
    private var triggerMouse = false
    private var triggerHeld = false
    private var attackKeyWas = false

    /** Seconds until the gun is loaded again; 0 or less once it is. */
    internal fun loadingLeft(vehicle: ArmedVehicle): Double = vehicle.turretSpec.round.reloadTicks / 20.0 - (System.nanoTime() - firedAtNanos) / 1.0e9

    internal fun loading(vehicle: ArmedVehicle): Boolean = loadingLeft(vehicle) > 0.0

    // Input.

    /** A mouse button, while gunning: attack fires, use (held) looks down the sight. True takes the click from the game. */
    @JvmStatic
    fun onMouseButton(button: Int, action: Int): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) return false
        val vehicle = gunning() ?: return false
        if (mc.options.keyUse.matchesMouse(button)) {
            useButton = button
            useMouse = action != GLFW.GLFW_RELEASE
            zoomed = useMouse || mc.options.keyUse.isDown
            return true
        }
        if (!mc.options.keyAttack.matchesMouse(button)) return false
        if (action == GLFW.GLFW_PRESS) pull(vehicle)
        triggerMouse = action != GLFW.GLFW_RELEASE
        triggerHeld = triggerMouse || mc.options.keyAttack.isDown
        triggerButton = button
        return true
    }

    /** The mouse wheel while gunning trims the sight's power. True takes it from the hotbar. */
    @JvmStatic
    fun onMouseScroll(notches: Double): Boolean {
        if (gunning() == null || Minecraft.getInstance().screen != null) return false
        Sight.trim(Math.signum(notches).toInt())
        return true
    }

    /** A pull of the trigger: sent to the server, which fires once the gun is loaded and laid, or clicks dry. */
    private fun pull(vehicle: ArmedVehicle) {
        val turret = vehicle.turret
        when {
            turret.roundsShown == 0 -> {
                refuse()
                Minecraft.getInstance().player?.displayClientMessage(Component.translatable("message.vehicleguns.no_rounds"), true)
            }
            (near && !braking()) || loadingLeft(vehicle) > KEEP_LOADING_SECONDS -> refuse()
            else -> sendPull(vehicle)
        }
    }

    private fun sendPull(vehicle: ArmedVehicle) {
        Net.toServer(GunFirePayload(vehicle.entity.id))
        pulledNanos = System.nanoTime()
        pulledShots = vehicle.turret.shotsFired
    }

    private fun refuse() {
        flashNanos = System.nanoTime()
        Minecraft.getInstance().player?.playSound(SoundEvents.DISPENSER_FAIL, 0.5f, 1.4f)
    }

    /** Whether the steadying still brakes the vehicle: a spot too near now may come right, so a pull is sent and waits. */
    private fun braking(): Boolean = (locked != null || zoomed) && way.length() > Turrets.EASING_SPEED

    /** The buttons, a tick: the use button held zooms and steadies; the trigger held pulls again as the gun is nearly loaded. */
    private fun buttons(vehicle: ArmedVehicle) {
        val mc = Minecraft.getInstance()
        // A mouse button let go while a screen took the release counts as let go.
        if (useMouse && (mc.screen != null || GLFW.glfwGetMouseButton(mc.window.window, useButton) != GLFW.GLFW_PRESS)) useMouse = false
        if (triggerMouse && (mc.screen != null || GLFW.glfwGetMouseButton(mc.window.window, triggerButton) != GLFW.GLFW_PRESS)) triggerMouse = false
        val useKey = mc.screen == null && mc.options.keyUse.isDown
        val attackKey = mc.screen == null && mc.options.keyAttack.isDown
        zoomed = useMouse || useKey
        if (attackKey && !attackKeyWas) pull(vehicle)
        attackKeyWas = attackKey
        triggerHeld = triggerMouse || attackKey
        val steady = zoomed || locked != null
        if (steady != steadySent) {
            steadySent = steady
            Net.toServer(GunSteadyPayload(vehicle.entity.id, steady))
        }
        if (!triggerHeld || vehicle.turret.roundsShown == 0 || (near && !braking()) || vehicle.turret.pullWaiting || loadingLeft(vehicle) > KEEP_LOADING_SECONDS) return
        // A pull just sent shows as waiting a tick or so later: not sent twice meanwhile.
        if ((System.nanoTime() - pulledNanos) / 1.0e9 < REPULL_SECONDS) return
        if (vehicle.turret.shotsFired != pulledShots && (System.nanoTime() - firedAtNanos) / 1.0e9 < REPULL_SECONDS) return
        sendPull(vehicle)
    }

    private fun lockKey(vehicle: ArmedVehicle) {
        val down = LOCK_KEY.isDown && Minecraft.getInstance().screen == null
        if (down && !lockHeld) lockOn(vehicle)
        if (!down && lockHeld && locked != null) unlock(click = true)
        lockHeld = down
    }

    /** Locks on the creature under the crosshair, or the one nearest it within [LOCK_CONE]; none, it clicks dry. */
    private fun lockOn(vehicle: ArmedVehicle) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val eye = camera(vehicle, player.yRot, player.xRot, 1f)
        val target = Aiming.aimedFrom(level, vehicle, player, eye, player.lookAngle).creature ?: nearest(vehicle, level, eye, player)
        if (target == null) { refuse(); return }
        locked = target
        lockedWay = Vec3.ZERO
        lockedWas = target.position()
        player.playSound(SoundEvents.LODESTONE_COMPASS_LOCK, 0.7f, 1.4f)
    }

    private fun nearest(vehicle: ArmedVehicle, level: Level, eye: Vec3, player: Player): Entity? {
        val look = player.lookAngle
        val cone = Math.cos(Math.toRadians(LOCK_CONE))
        val reach = vehicle.turretSpec.round.reach
        val ship = vehicle.entity
        return level.getEntitiesOfClass(LivingEntity::class.java, AABB(eye, eye).inflate(reach)) {
            it !== player && it !== ship && it.rootVehicle !== ship && vehicle.targetRules.gunnerMayHit(it)
        }.map { it to it.boundingBox.center.subtract(eye) }
            .filter { (_, d) -> d.length() <= reach && d.normalize().dot(look) >= cone }
            .filter { (it, _) -> visible(level, eye, it, player) }
            .maxByOrNull { (_, d) -> d.normalize().dot(look) }?.first
    }

    /** Whether any of [creature] shows from [eye]: its middle, its back or its head. */
    private fun visible(level: Level, eye: Vec3, creature: Entity, player: Player): Boolean {
        val box = creature.boundingBox
        return listOf(box.center, Vec3(box.center.x, box.maxY - 0.1, box.center.z), creature.eyePosition).any {
            level.clip(ClipContext(eye, it, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS
        }
    }

    private fun unlock(click: Boolean) {
        if (locked == null) return
        locked = null
        if (click) Minecraft.getInstance().player?.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 0.8f)
    }

    /**
     * Where to lay on [target] so a round comes down on it: where it will be when the round gets there along its way
     * on the ground, the flight found again for that spot.
     */
    private fun lead(vehicle: ArmedVehicle, target: Entity, pivot: Vec3): Vec3 {
        val at = Aiming.standing(target.level(), target)
        val ground = Vec3(lockedWay.x, 0.0, lockedWay.z)
        val riders = Aiming.riders(vehicle)
        var spot = at
        repeat(LEAD_PASSES) {
            val lay = Aiming.chooseLay(vehicle, spot, pivot, way) { Aiming.floor(vehicle, it, riders, pivot) }.first
            // Out of reach for now, a lay's flight is the round's whole life: the flight is reckoned from the distance.
            val ticks = if (lay.reaches) lay.ticks.toDouble() else Math.hypot(at.x - pivot.x, at.z - pivot.z) / vehicle.turretSpec.round.speed
            spot = at.add(ground.scale(ticks))
        }
        return spot
    }

    // The tick.

    @JvmStatic
    fun tick() {
        val mc = Minecraft.getInstance()
        val vehicle = gunning()
        val player = mc.player
        val level = mc.level
        if (vehicle == null || player == null || level == null) {
            if (boarded != null) leave(mc)
            return
        }
        if (boarded !== vehicle) board(mc, vehicle, player)
        val turret = vehicle.turret
        if (turret.shotsFired != shotsSeen) {
            if (shotsSeen >= 0) fired(mc, player)
            shotsSeen = turret.shotsFired
        }
        // Running dry is heard, not found out on the next pull; loaded again, the breech is heard closing.
        if (roundsWas > 0 && turret.roundsShown == 0) player.playSound(SoundEvents.CHAIN_PLACE, 0.6f, 1.6f)
        roundsWas = turret.roundsShown
        val loading = loading(vehicle)
        if (wasLoading && !loading) {
            player.playSound(SoundEvents.CROSSBOW_LOADING_END.value(), 0.7f, 0.6f)
            player.playSound(SoundEvents.IRON_TRAPDOOR_CLOSE, 0.6f, 0.6f)
        }
        wasLoading = loading
        // The turret's grind while it swings, rising with its pace.
        val swing = Math.abs(Mth.wrapDegrees(turret.traverse - turretWas))
        turretWas = turret.traverse
        if (swing > 0.4f) {
            swungNanos = System.nanoTime()
            if (--turnSoundTicks <= 0) {
                turnSoundTicks = 5
                player.playSound(SoundEvents.GRINDSTONE_USE, 0.1f + 0.03f * swing.coerceAtMost(6f), 0.45f + 0.06f * swing.coerceAtMost(6f))
            }
        }
        val at = vehicle.entity.position()
        wasAt?.let { way = way.add(at.subtract(it).subtract(way).scale(WAY_EASE)) }
        wasAt = at
        val pivot = Aiming.pivot(vehicle)
        lockKey(vehicle)
        locked?.let { target ->
            if (!target.isAlive || target.isRemoved || target.level() !== level || target.position().distanceTo(pivot) > Aiming.SIGHT_RANGE) unlock(click = true)
            else {
                lockedWas?.let { lockedWay = lockedWay.add(target.position().subtract(it).subtract(lockedWay).scale(LOCK_WAY_EASE)) }
                lockedWas = target.position()
            }
        }
        val target = locked
        val spot = if (target != null) lead(vehicle, target, pivot)
            else Aiming.aimedFrom(level, vehicle, player, camera(vehicle, player.yRot, player.xRot, 1f), player.lookAngle).spot
        aim = spot
        sendAim(vehicle, spot, level.gameTime)
        val riders = Aiming.riders(vehicle)
        val (lay, lobbing) = layCache.get(level.gameTime, spot, way, vehicle.entity.yRot) {
            Aiming.chooseLay(vehicle, spot, pivot, way) { Aiming.floor(vehicle, it, riders, pivot) }
        }
        reaches = lay.reaches
        near = lay.near
        lob = lobbing
        // Where a round fired now would come down, from the barrel as it lies.
        val round = vehicle.turretSpec.round
        val flight = ArrayList<Vec3>()
        val landing = Ballistics.landing(pivot, vehicle.turretSpec.barrel, Aiming.barrel(vehicle), way, round.speed, round.gravity, round.lifeTicks, flight) { a, b ->
            level.clip(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player)).takeIf { it.type != HitResult.Type.MISS }?.location
        }
        lands = landing
        path = if (landing != null) flight else emptyList()
        // Laid as the server has it: it has the lay and the mount exactly. Coming on target is heard, so the eyes can stay on it.
        val wasLaid = laid
        laid = turret.laid
        if (laid && !wasLaid && reaches && (System.nanoTime() - laidSoundNanos) / 1.0e9 > 0.4) {
            laidSoundNanos = System.nanoTime()
            player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.35f, 1.6f)
        }
        // A pull the server kept and then let go without firing.
        val kept = turret.pullWaiting
        if (wasKept && !kept && turret.shotsFired == pulledShots && !triggerHeld) refuse()
        wasKept = kept
        buttons(vehicle)
        machineGunKey(vehicle, player, level)
        seekerKey(vehicle, player, level)
    }

    /**
     * The seekers' key, held: said to the server every tick with what the gunner marks, the locked target, else the
     * creature under the crosshair, else the one nearest it within [SEEK_CONE]; one a round of its kind may make for
     * (in the water for a torpedo, not wholly under it for a missile). -1 for none: the server then looks near the sight.
     */
    private fun seekerKey(vehicle: ArmedVehicle, player: Player, level: Level) {
        val spec = vehicle.turretSpec.seekers
        seekersHeld = spec != null && SEEKER_KEY.isDown && Minecraft.getInstance().screen == null
        if (spec == null || !seekersHeld) { marked = null; return }
        val ship = vehicle.entity
        val reach = spec.range + MARK_SLACK
        fun fits(e: Entity): Boolean = e.isAlive && e !== player && e !== ship && e.rootVehicle !== ship && vehicle.targetRules.gunnerMayHit(e) &&
            (if (spec.kind == SeekerSpec.Kind.TORPEDO) e.isInWater else !SeekerEntity.submerged(level, e)) && e.distanceTo(player) <= reach
        val eye = camera(vehicle, player.yRot, player.xRot, 1f)
        val look = player.lookAngle
        marked = locked?.takeIf { fits(it) } ?: run {
            val end = eye.add(look.scale(reach))
            val wall = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).let { if (it.type == HitResult.Type.MISS) end else it.location }
            ProjectileUtil.getEntityHitResult(level, player, eye, wall, AABB(eye, wall).inflate(1.0)) { it.isPickable && fits(it) }?.entity
        } ?: run {
            val cone = Math.cos(Math.toRadians(SEEK_CONE))
            level.getEntitiesOfClass(LivingEntity::class.java, AABB(eye, eye).inflate(reach)) { fits(it) }
                .map { it to it.boundingBox.center.subtract(eye).normalize().dot(look) }
                .filter { it.second >= cone }
                .maxByOrNull { it.second }?.first
        }
        if (!vehicle.turret.seekersSpent) Net.toServer(GunSeekerPayload(ship.id, marked?.id ?: -1))
    }

    /**
     * The machine guns' key, held: said to the server every tick with where the crosshair is (the first creature a
     * bullet may hit, at its body, else the first block or the water, else far out along it); locked on, the locked
     * target's body where it will be when a bullet gets there.
     */
    private fun machineGunKey(vehicle: ArmedVehicle, player: Player, level: Level) {
        val specs = vehicle.turretSpec.machineGuns
        machineGunsHeld = specs.isNotEmpty() && MACHINE_GUN_KEY.isDown && Minecraft.getInstance().screen == null
        if (!machineGunsHeld || vehicle.turret.overheated) return
        val ship = vehicle.entity
        val at = locked?.let { target ->
            val body = target.boundingBox.center
            body.add(lockedWay.scale(body.distanceTo(ship.position()) / specs[0].bulletSpeed))
        } ?: run {
            val eye = camera(vehicle, player.yRot, player.xRot, 1f)
            val end = eye.add(player.lookAngle.scale(Aiming.SIGHT_RANGE))
            val block = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player))
            val blockEnd = if (block.type == HitResult.Type.MISS) end else block.location
            ProjectileUtil.getEntityHitResult(level, player, eye, blockEnd, AABB(eye, blockEnd).inflate(1.0)) {
                it.isPickable && it !== ship && it.rootVehicle !== ship && vehicle.targetRules.gunnerMayHit(it)
            }?.location ?: blockEnd
        }
        Net.toServer(GunMachinePayload(ship.id, at.x, at.y, at.z))
    }

    /** The aim, sent as it moves and at least twice a second: the server lays on what this client's sight is on. */
    private fun sendAim(vehicle: ArmedVehicle, spot: Vec3, now: Long) {
        val was = aimSent
        if (was != null && was.distanceToSqr(spot) < AIM_SEND_SLACK * AIM_SEND_SLACK && now - aimSentTick < AIM_HEARTBEAT_TICKS) return
        aimSent = spot
        aimSentTick = now
        Net.toServer(GunAimPayload(vehicle.entity.id, spot.x, spot.y, spot.z))
    }

    private fun board(mc: Minecraft, vehicle: ArmedVehicle, player: Player) {
        boarded = vehicle
        // Into the gun's own camera, whatever view they had: the others are not taken while aboard (OptionsGunnerMixin).
        if (!mc.options.cameraType.isFirstPerson) {
            viewBefore = mc.options.cameraType
            mc.options.cameraType = CameraType.FIRST_PERSON
        }
        // The same short hint at every gun: its own keys only, the mouse's found by trying them.
        val hint = Component.translatable("message.vehicleguns.hint", LOCK_KEY.translatedKeyMessage)
        if (vehicle.turretSpec.machineGuns.isNotEmpty()) hint.append(Component.translatable("message.vehicleguns.hint.machine_guns", MACHINE_GUN_KEY.translatedKeyMessage))
        vehicle.turretSpec.seekers?.let {
            val kind = if (it.kind == SeekerSpec.Kind.TORPEDO) "torpedoes" else "missiles"
            hint.append(Component.translatable("message.vehicleguns.hint.$kind", SEEKER_KEY.translatedKeyMessage))
        }
        player.displayClientMessage(hint, true)
    }

    private fun leave(mc: Minecraft) {
        viewBefore?.let { mc.options.cameraType = it }
        viewBefore = null
        boarded = null
        aim = null; lands = null; path = emptyList(); flying = null; flyingPath = emptyList()
        zoomed = false; wasKept = false; wasAt = null; locked = null; lob = false; machineGunsHeld = false; seekersHeld = false; marked = null
        triggerHeld = false; useMouse = false; triggerMouse = false; attackKeyWas = false
        roundsWas = -1; shotsSeen = -1; steadySent = false; aimSent = null
        layCache.clear()
        Sight.reset()
    }

    private fun fired(mc: Minecraft, player: Player) {
        firedAtNanos = System.nanoTime()
        // Felt as well as heard by the one who fired it: a deep thump close by, the breech thrown open after.
        player.playSound(SoundEvents.GENERIC_EXPLODE.value(), 0.5f, 0.4f)
        mc.soundManager.playDelayed(SimpleSoundInstance.forUI(SoundEvents.IRON_TRAPDOOR_OPEN, 0.55f, 0.5f), 12)
        // The round now in the air: where it comes down and its arc, pinned until it does.
        flying = lands
        flyingPath = path
    }

    /** The server's word on where a round of theirs came down and what it hit. */
    fun hit(payload: GunHitPayload) {
        val player = Minecraft.getInstance().player ?: return
        flying = null
        flyingPath = emptyList()
        killed = payload.kills > 0
        ShellBursts.thump()
        if (payload.hits > 0) {
            hitNanos = System.nanoTime()
            player.playSound(SoundEvents.ARROW_HIT_PLAYER, 0.6f, if (killed) 0.6f else 1.2f)
        }
    }

    // The camera.

    /** Where the gunner's camera is this frame. Called once a frame as the camera is placed (CameraGunnerMixin). */
    @JvmStatic
    fun cameraFrame(vehicle: ArmedVehicle, rider: Player, partial: Float): Vec3 =
        Sight.frame(vehicle, rider, partial).also {
            if (Math.abs(Mth.wrapDegrees(rider.yRot - lastYaw)) > 1e-3f || Math.abs(rider.xRot - lastPitch) > 1e-3f) movedNanos = System.nanoTime()
            lastYaw = rider.yRot
            lastPitch = rider.xRot
        }

    /** The gunner's camera for a look of [yaw], [pitch], with this frame's easing of the vehicle's bob. */
    fun camera(vehicle: ArmedVehicle, yaw: Float, pitch: Float, partial: Float): Vec3 = Sight.camera(vehicle, yaw, pitch, partial)

    /** The field of view, narrowed down the sight and widened a moment as the gun fires. */
    @JvmStatic
    fun fov(fov: Double): Double {
        if (gunning() == null || !Minecraft.getInstance().options.cameraType.isFirstPerson) return fov
        val t = (System.nanoTime() - firedAtNanos) / 1.0e9
        val punch = if (t in 0.0..0.3) 1.0 + PUNCH * Math.exp(-t / 0.08) else 1.0
        return fov * Sight.zoomShare(zoomed) * punch
    }

    /** How much of the mouse's turn reaches the view: less zoomed in, so the aim keeps its feel. */
    @JvmStatic
    fun mouseShare(): Double = if (gunning() != null) Sight.zoomShare(zoomed).toDouble() else 1.0

    /** The gun's kick in the view as it fires, degrees of (pitch, yaw), settling within a second. */
    @JvmStatic
    fun kick(): FloatArray {
        if (gunning() == null || !GunSettings.screenShake) return NO_KICK
        val t = (System.nanoTime() - firedAtNanos) / 1.0e9
        if (t < 0.0 || t > 1.2) return NO_KICK
        val up = KICK_DEGREES * (if (t < KICK_RISE) t / KICK_RISE else Math.exp(-(t - KICK_RISE) / KICK_SETTLE))
        val shake = SHAKE_DEGREES * 0.6 * Math.exp(-t / 0.15) * Math.sin(t * 53.0 + 1.0)
        return floatArrayOf(-up.toFloat(), shake.toFloat())
    }

    /** Whether the game's own crosshair, hotbar and block outline give way to the gun's sight. */
    @JvmStatic
    fun ownsView(): Boolean = gunning() != null && aim != null && Minecraft.getInstance().options.cameraType.isFirstPerson

    private val NO_KICK = floatArrayOf(0f, 0f)
    private const val NEVER = Long.MIN_VALUE / 4
    private const val LEAD_PASSES = 2
    /** Locking with the crosshair not quite on a creature takes the one nearest it within this many degrees. */
    private const val LOCK_CONE = 6.0
    /** Seekers with nothing under the crosshair mark the creature nearest it within this many degrees, this far past their range. */
    private const val SEEK_CONE = 10.0
    private const val MARK_SLACK = 8.0
    /** Held, the trigger is pulled again no sooner than this after the last pull or shot, seconds. */
    private const val REPULL_SECONDS = 0.3
    /** A pull with more than this of loading left is too soon, seconds. */
    private const val KEEP_LOADING_SECONDS = 1.0
    /** How much of each tick's step the vehicle's and a locked target's smoothed way take up. */
    private const val WAY_EASE = 0.35
    private const val LOCK_WAY_EASE = 0.3
    /** The aim is sent again once it moves this far, blocks, or after this many ticks regardless. */
    private const val AIM_SEND_SLACK = 0.05
    private const val AIM_HEARTBEAT_TICKS = 10L
    /** The view widens this share of itself for a moment as the gun fires. */
    private const val PUNCH = 0.05
    /** The kick: degrees up, reached in this long and settled over this long, seconds; and the shake. */
    private const val KICK_DEGREES = 2.5
    private const val KICK_RISE = 0.04
    private const val KICK_SETTLE = 0.3
    private const val SHAKE_DEGREES = 0.9
}
