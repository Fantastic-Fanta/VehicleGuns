package dev.vehicleguns.gun

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.MountHandling
import dev.vehicleguns.api.entity
import dev.vehicleguns.ballistics.Ballistics
import dev.vehicleguns.ballistics.DeckClearance
import dev.vehicleguns.ballistics.TargetTrack
import dev.vehicleguns.entity.ShellEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Phantom
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max

/**
 * The server's hand on every vehicle gun. With a gunner aboard it lays the gun on the spot their sight is on and fires
 * when they pull the trigger; with none it guards on its own, laying on hostile mobs and firing a guard's round.
 *
 * The mount moves as a real one does ([dev.vehicleguns.ballistics.MountAxis]): gathering speed and settling on its
 * bearing, keeping pace with a target crossing its sights, lifting the barrel over the vehicle's riders as it swings
 * past them. A round goes where the barrel points when it fires, with the vehicle's own velocity added.
 */
object Turrets {
    /**
     * The client's held aim stands this long without another, so a lag spike keeps its aim (the client sends it at
     * least twice a second), and may be this far past the sight's range.
     */
    private const val HELD_AIM_TICKS = 40L
    private const val HELD_AIM_SLACK = 16.0
    /** A gunner's gun is laid within this of its lay, degrees, bearing and elevation. */
    private const val LAID_DEGREES = 0.5
    /** A pull waits this long for the gun to be laid; one with more than [KEEP_LOADING_TICKS] of loading left is too soon. */
    private const val KEEP_TICKS = 60L
    private const val KEEP_LOADING_TICKS = 20L
    /** A pull waiting for a braking vehicle to bring the spot into reach fires short this long before it would be let go. */
    private const val LAST_CHANCE_TICKS = 6L
    /** A vehicle faster than this, blocks a tick, is still being braked for its gunner. */
    const val EASING_SPEED = 0.2
    /** A gunner's goal moving more than this in a tick, degrees, has jumped (they looked elsewhere): no pace to keep. */
    private const val FOLLOW_JUMP = 2.0
    /** Within this of its bearing the turret always turns the short way; this near dead astern it keeps the way it is turning. */
    private const val SHORT_WAY_DEGREES = 45.0
    private const val KEEP_WAY_DEGREES = 20.0
    /** How close to the solution a guarding gun must be laid to fire, degrees, at least and at most. */
    private const val LAID_MIN = 0.6
    private const val LAID_MAX = 2.0
    /** A guarding gun's target it cannot fire on this long is given up; one unseen this long is lost. */
    private const val BLOCKED_TICKS = 60L
    private const val UNSEEN_TICKS = 40L
    /** Target picking: the nearest this many are weighed; nudges, in blocks of distance, for the one already laid on and a hurt one. */
    private const val CANDIDATES = 8
    private const val CURRENT_BONUS = 10.0
    private const val HURT_BONUS = 3.0
    /** A target this many degrees off where the gun points counts as a block further. */
    private const val DEGREES_PER_BLOCK = 20.0
    /** A guarding gun keeping watch turns at this share of its top pace. */
    private const val WATCH_PACE = 0.3

    /** Runs [vehicle]'s gun for a tick. Call from the vehicle's `tick()`, on both sides. */
    @JvmStatic
    fun tick(vehicle: ArmedVehicle) {
        val guns = vehicle.turretSpec.machineGuns
        vehicle.turret.keepPrevious(guns.size) { guns[it].rest }
        val level = vehicle.entity.level() as? ServerLevel ?: run { clientTick(vehicle); return }
        val gun = vehicle.turret.gunnery
        val now = level.gameTime
        val at = vehicle.entity.position()
        gun.vehicleVelocity = gun.vehicleWas?.let { at.subtract(it) } ?: Vec3.ZERO
        gun.vehicleWas = at
        val gunner = vehicle.gunner as? ServerPlayer
        val pivot = Aiming.pivot(vehicle)
        when {
            gunner != null -> underGunner(level, vehicle, gun, gunner, now, pivot)
            vehicle.turretSpec.guard != null -> { gun.dropGunner(); guard(level, vehicle, gun, now, pivot) }
            else -> { gun.dropGunner(); gun.dropTarget(); rest(vehicle, gun) }
        }
        if (gunner == null) {
            vehicle.turret.laid = false
            vehicle.turret.pullWaiting = false
        }
        MachineGuns.tick(level, vehicle, gun, gunner, now)
        if (now % UNSEEN_TICKS == 0L) gun.forgetUnseen(now, UNSEEN_TICKS)
    }

    /** The client's own per-vehicle tick (tracers and muzzle flashes), set by the client's setup. */
    @JvmStatic
    var clientTick: (ArmedVehicle) -> Unit = {}

    /** Whether [vehicle]'s gunner asks for a steady platform (down the sight, or locked on): slow the vehicle while it is true. */
    @JvmStatic
    fun wantsSteady(vehicle: ArmedVehicle): Boolean = vehicle.gunner != null && vehicle.turret.gunnery.steady

    // Under a gunner.

    private fun underGunner(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: ServerPlayer, now: Long, pivot: Vec3) {
        if (gun.target >= 0) gun.dropTarget()
        val e = vehicle.entity
        val riders = Aiming.riders(vehicle)
        // The mount holds its bearing in the world as the vehicle turns under it: only the gunner's own change of aim is its to swing.
        gun.headingWas?.let { was ->
            val turn = Mth.wrapDegrees(e.yRot - was).toDouble()
            gun.traverse.at -= turn
            gun.lastGoal = gun.lastGoal?.let { (t, el) -> (t - turn) to el }
        }
        gun.headingWas = e.yRot
        val aim = gun.heldAim?.takeIf { now - gun.heldAimAt <= HELD_AIM_TICKS } ?: Aiming.aimedAt(level, vehicle, gunner).spot
        val (lay, lob) = gun.layCache.get(now, aim, gun.vehicleVelocity, e.yRot) {
            Aiming.chooseLay(vehicle, aim, pivot, gun.vehicleVelocity) { Aiming.floor(vehicle, it, riders, pivot) }
        }
        gun.lay = lay
        gun.lob = lob
        val goal = Ballistics.angles(e.yRot.toDouble(), lay.dir)
        lay(vehicle, gun, goal, riders, pivot, vehicle.turretSpec.gunnerMount, keepPace = true)
        publishAngles(vehicle, gun)
        vehicle.turret.laid = abs(Mth.wrapDegrees(gun.traverse.at - goal.first)) <= LAID_DEGREES && abs(gun.elevation.at - goal.second) <= LAID_DEGREES
        pulled(level, vehicle, gun, gunner, now)
        vehicle.turret.pullWaiting = gun.pullUntil >= now
        vehicle.turret.roundsShown = vehicle.roundsLeft(gunner)
    }

    /**
     * The gunner pulls the trigger: the round fires the first tick the gun is loaded and laid, within [KEEP_TICKS]. A
     * pull made with the spot in reach is dropped if it passes out of reach first; one made out of reach fires short on
     * the furthest lay. A pull too soon, too close, with no rounds, or from anyone but the gunner, does nothing.
     */
    fun fireByGunner(player: ServerPlayer, vehicleId: Int) {
        val vehicle = gunnedBy(player, vehicleId) ?: return
        val gun = vehicle.turret.gunnery
        val now = player.serverLevel().gameTime
        if (vehicle.turretSpec.round.reloadTicks - (now - gun.firedAt) > KEEP_LOADING_TICKS) return
        if (vehicle.roundsLeft(player) == 0) return
        val lay = gun.lay
        if (lay?.near == true && !easing(gun)) return
        gun.pullUntil = now + KEEP_TICKS
        gun.pullFar = lay?.reaches == false
        pulled(player.serverLevel(), vehicle, gun, player, now)
    }

    /**
     * The spot the gunner's client holds its sight on. Under way the client sees its vehicle a little behind where the
     * server has it, so the gun is laid on the spot they see rather than one picked here from their view angles. Taken
     * only for a finite spot within the sight's reach of the vehicle; otherwise ignored.
     */
    fun aimByGunner(player: ServerPlayer, vehicleId: Int, spot: Vec3) {
        val vehicle = gunnedBy(player, vehicleId) ?: return
        if (!spot.x.isFinite() || !spot.y.isFinite() || !spot.z.isFinite()) return
        if (spot.distanceTo(vehicle.entity.position()) > Aiming.SIGHT_RANGE + HELD_AIM_SLACK || spot.y < player.level().minBuildHeight) return
        val gun = vehicle.turret.gunnery
        gun.heldAim = spot
        gun.heldAimAt = player.serverLevel().gameTime
    }

    /**
     * The gunner holds the machine guns' key, saying where the crosshair is: each gun whose reach takes it in turns to
     * it and fires full auto while that keeps coming. Taken only for a finite spot within the sight's reach.
     */
    fun machineGunByGunner(player: ServerPlayer, vehicleId: Int, spot: Vec3) {
        val vehicle = gunnedBy(player, vehicleId) ?: return
        if (!spot.x.isFinite() || !spot.y.isFinite() || !spot.z.isFinite()) return
        if (spot.distanceTo(vehicle.entity.position()) > Aiming.SIGHT_RANGE + HELD_AIM_SLACK) return
        val gun = vehicle.turret.gunnery
        gun.machineGunAim = spot
        gun.machineGunAimAt = player.serverLevel().gameTime
    }

    /** The gunner asks for a steady platform, or lets it go ([wantsSteady]). */
    fun steadyByGunner(player: ServerPlayer, vehicleId: Int, steady: Boolean) {
        gunnedBy(player, vehicleId)?.turret?.gunnery?.steady = steady
    }

    /** The vehicle [vehicleId] if [player] is its gunner. */
    private fun gunnedBy(player: ServerPlayer, vehicleId: Int): ArmedVehicle? =
        (player.serverLevel().getEntity(vehicleId) as? ArmedVehicle)?.takeIf { it.gunner === player }

    /** Whether the gunner's steadying is still braking the vehicle: a spot out of reach now may come right. */
    private fun easing(gun: Gunnery): Boolean = gun.steady && gun.vehicleVelocity.length() > EASING_SPEED

    /** A waiting pull, a tick: fired once the gun is loaded and laid, dropped as [fireByGunner] says. */
    private fun pulled(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: ServerPlayer, now: Long) {
        if (gun.pullUntil < now) return
        val lay = gun.lay
        // While the vehicle is still braking the spot comes right as its velocity, which carries the round, falls away:
        // the pull waits for that, firing short (or letting go, too near) only at the last of its while.
        val waits = easing(gun) && gun.pullUntil - now > LAST_CHANCE_TICKS
        if (lay == null || ((lay.near || (!gun.pullFar && !lay.reaches)) && !waits)) { gun.pullUntil = Gunnery.NEVER; return }
        if (now - gun.firedAt < vehicle.turretSpec.round.reloadTicks || !vehicle.turret.laid) return
        if ((lay.near || !lay.reaches) && waits) return
        gun.pullUntil = Gunnery.NEVER
        if (!vehicle.takeRound(gunner)) return
        gun.firedAt = now
        fireRound(level, vehicle, gun, gunner)
    }

    // Guarding on its own.

    private fun guard(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, now: Long, pivot: Vec3) {
        val spec = vehicle.turretSpec
        val guard = spec.guard ?: return
        val e = vehicle.entity
        val riders = Aiming.riders(vehicle)
        var target = level.getEntity(gun.target)?.takeIf { stillGood(level, vehicle, gun, it, pivot, now) }
        if (target == null) gun.target = -1
        if (target == null || (now + e.id) % guard.searchTicks == 0L) {
            val better = pick(level, vehicle, gun, pivot, riders, target)
            if (better !== target) {
                gun.dropTarget()
                target = better
                gun.target = better?.id ?: -1
            }
        }

        val solution = target?.let { solve(vehicle, gun, it, pivot, riders, now) }
        var watch: Pair<Double, Double>? = null
        val goal = when {
            solution != null -> {
                gun.idleSince = now
                gun.watchFrom = Gunnery.NEVER
                Ballistics.angles(e.yRot.toDouble(), solution.dir)
            }
            now - gun.idleSince > guard.watchAfterTicks -> {
                // Its watch begins where the gun already is, if that is within the sweep.
                if (gun.watchFrom == Gunnery.NEVER) {
                    gun.watchFrom = now
                    gun.watchPhase = Math.asin((Mth.wrapDegrees(gun.traverse.at) / guard.watchArc).coerceIn(-1.0, 1.0))
                }
                val phase = gun.watchPhase + (now - gun.watchFrom) * 2 * Math.PI / guard.watchTicks
                watch = guard.watchArc * Math.cos(phase) * 2 * Math.PI / guard.watchTicks to 0.0
                guard.watchArc * Math.sin(phase) to guard.watchElevation
            }
            else -> null
        }
        lay(vehicle, gun, goal, riders, pivot, spec.guardMount, keepPace = target != null, watch = watch)
        publishAngles(vehicle, gun)
        if (target == null || solution == null) { gun.blockedSince = now; return }
        val clear = Aiming.clearPath(level, vehicle, Aiming.muzzle(vehicle), Aiming.aimPoint(target), target)
        if (clear) gun.blockedSince = now else if (now - gun.blockedSince > BLOCKED_TICKS) gun.target = -1
        // A lob goes over what is in the way.
        if ((clear || gun.autoLob) && laid(vehicle, solution, target, pivot) && now - gun.firedAt >= spec.round.reloadTicks) {
            gun.firedAt = now
            fireRound(level, vehicle, gun, null)
        }
    }

    /**
     * The lay onto [target]: where it stands (on the water over it, swimming), led along the ground by its velocity over
     * the round's flight, flat or lobbed as a gunner's sight would choose. Null when it is too near or out of reach.
     */
    private fun solve(vehicle: ArmedVehicle, gun: Gunnery, target: Entity, pivot: Vec3, riders: List<DeckClearance.Rider>, now: Long): Ballistics.Solution? {
        val track = gun.track(target, now)
        val feet = Aiming.standing(vehicle.entity.level(), target)
        val ground = Vec3(track.velocity.x, 0.0, track.velocity.z)
        val floorAt = { t: Double -> Aiming.floor(vehicle, t, riders, pivot) }
        val (lay, lob) = gun.layCache.get(now, feet, gun.vehicleVelocity, vehicle.entity.yRot, ground) {
            var choice = Aiming.chooseLay(vehicle, feet, pivot, gun.vehicleVelocity, floorAt)
            repeat(LEAD_PASSES) { choice = Aiming.chooseLay(vehicle, feet.add(ground.scale(choice.first.ticks.toDouble())), pivot, gun.vehicleVelocity, floorAt) }
            choice
        }
        gun.autoLob = lob
        return if (!lay.reaches || lay.near) null else Ballistics.Solution(lay.dir, lay.ticks, lay.miss)
    }

    private const val LEAD_PASSES = 2

    /** Whether [target] is still one to shoot at: allowed, in range, not under water, and in sight lately. */
    private fun stillGood(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, target: Entity, pivot: Vec3, now: Long): Boolean {
        val range = vehicle.turretSpec.guard?.range ?: return false
        if (!vehicle.targetRules.guardMayHit(target) || target.level() !== level) return false
        if (Aiming.aimPoint(target).distanceToSqr(pivot) > range * range || submerged(level, target)) return false
        if ((now + vehicle.entity.id) % 5L == 0L && inSight(level, pivot, target, vehicle.turretSpec.barrel)) gun.lastSeenAt = now
        return now - gun.lastSeenAt < UNSEEN_TICKS
    }

    /**
     * The target that matters most of the hostile mobs it could hit: a creeper by the vehicle, a mob going for someone
     * and a diving phantom before the nearest; the one already laid on keeps its place unless another is clearly worse
     * to leave, and one near where the barrel points comes before one behind it.
     */
    private fun pick(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, pivot: Vec3, riders: List<DeckClearance.Rider>, current: Entity?): Entity? {
        val range = vehicle.turretSpec.guard?.range ?: return null
        val e = vehicle.entity
        val rules = vehicle.targetRules
        val barrel = Aiming.barrel(vehicle)
        val near = level.getEntitiesOfClass(Mob::class.java, AABB(pivot, pivot).inflate(range)) {
            rules.guardMayHit(it) && Aiming.aimPoint(it).distanceToSqr(pivot) <= range * range
        }.sortedBy { it.distanceToSqr(pivot) }.take(CANDIDATES)
        var best: Entity? = null
        var bestScore = Double.MAX_VALUE
        for (mob in near) {
            if (submerged(level, mob)) continue
            val at = Aiming.aimPoint(mob)
            var score = at.distanceTo(pivot)
            if (mob is Creeper && mob.distanceTo(e) < 10.0) score -= 8.0
            val after = mob.target
            if (after != null && (after is Player || after is Villager || after.rootVehicle is ArmedVehicle)) score -= 5.0
            if (mob is Phantom) score -= 3.0
            if (mob === current) score -= CURRENT_BONUS
            if (mob.health < mob.maxHealth) score -= HURT_BONUS
            val dir = at.subtract(pivot).normalize()
            score += Ballistics.apart(barrel, dir) / DEGREES_PER_BLOCK
            if (score >= bestScore) continue
            if (!inSight(level, pivot, mob, vehicle.turretSpec.barrel)) continue
            // One it could only shoot at through its riders, or down into its own vehicle, is no target.
            val (t, el) = Ballistics.angles(e.yRot.toDouble(), dir)
            if (el < Aiming.floor(vehicle, t, riders, pivot) - 0.5) continue
            if (!Aiming.clearPath(level, vehicle, pivot.add(dir.scale(vehicle.turretSpec.barrel)), at, mob)) continue
            best = mob
            bestScore = score
        }
        if (best != null) gun.lastSeenAt = level.gameTime
        return best
    }

    private fun submerged(level: ServerLevel, target: Entity): Boolean =
        !level.getFluidState(net.minecraft.core.BlockPos.containing(target.position().add(0.0, target.bbHeight * 0.9, 0.0))).isEmpty

    /** Whether the gun has a clear view of [target], from past its own barrel so the vehicle never blocks it. */
    private fun inSight(level: ServerLevel, pivot: Vec3, target: Entity, barrel: Double): Boolean {
        val to = Aiming.aimPoint(target)
        val start = pivot.add(to.subtract(pivot).normalize().scale(barrel))
        return level.clip(ClipContext(start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).type == HitResult.Type.MISS
    }

    /** Whether the barrel lies on [solution] closely enough for a round to meet [target]. */
    private fun laid(vehicle: ArmedVehicle, solution: Ballistics.Solution, target: Entity, pivot: Vec3): Boolean {
        val far = max(1.0, target.position().distanceTo(pivot))
        val allowed = Math.toDegrees(Math.atan2(target.bbWidth * 0.5 + 0.2, far)).coerceIn(LAID_MIN, LAID_MAX)
        return Ballistics.apart(Aiming.barrel(vehicle), solution.dir) <= allowed
    }

    // The mount.

    /** Nothing to lay on and nobody laying: the mount comes to rest where it is. */
    private fun rest(vehicle: ArmedVehicle, gun: Gunnery) {
        val mount = vehicle.turretSpec.guardMount
        gun.traverse.coast(mount.traverseAccel)
        gun.elevation.coast(mount.elevateAccel)
        publishAngles(vehicle, gun)
    }

    private fun publishAngles(vehicle: ArmedVehicle, gun: Gunnery) {
        vehicle.turret.traverse = Mth.wrapDegrees(gun.traverse.at).toFloat()
        vehicle.turret.elevation = gun.elevation.at.toFloat()
    }

    /**
     * Moves the mount a tick towards [goal] (traverse, elevation), lifting the barrel over [riders] as it swings past
     * them. With [keepPace] it keeps pace with how the goal moved since last tick (a target or a gunner's spot crossing
     * its sights); keeping [watch] it keeps pace with its own sweep and turns unhurried.
     */
    private fun lay(vehicle: ArmedVehicle, gun: Gunnery, goal: Pair<Double, Double>?, riders: List<DeckClearance.Rider>, pivot: Vec3,
                    mount: MountHandling, keepPace: Boolean, watch: Pair<Double, Double>? = null) {
        val spec = vehicle.turretSpec
        val t = gun.traverse
        val e = gun.elevation
        if (goal == null || !keepPace) {
            gun.lastGoal = null
            gun.followTraverse = 0.0
            gun.followElevation = 0.0
        }
        if (goal == null) {
            t.coast(mount.traverseAccel)
            e.coast(mount.elevateAccel)
            return
        }
        val (wantT, wantE) = goal
        if (keepPace) {
            gun.lastGoal?.let { (lt, le) ->
                val dt = Mth.wrapDegrees(wantT - lt)
                val de = wantE - le
                if (abs(dt) > FOLLOW_JUMP || abs(de) > FOLLOW_JUMP) { gun.followTraverse = 0.0; gun.followElevation = 0.0 }
                else {
                    gun.followTraverse = (gun.followTraverse + dt) * 0.5
                    gun.followElevation = (gun.followElevation + de) * 0.5
                }
            }
            gun.lastGoal = goal
        }
        if (watch != null) { gun.followTraverse = watch.first; gun.followElevation = watch.second }
        // The same few bearings are asked about many times a tick: worked out once each, to a degree.
        val floors = HashMap<Int, Double>()
        val floorAt = { tr: Double -> floors.getOrPut(Math.round(Mth.wrapDegrees(tr)).toInt()) { Aiming.floor(vehicle, tr, riders, pivot) } }
        // The short way round, but for riders, when going round the other way can beat lifting the barrel over them;
        // and a swing begun on a target nearly astern keeps its way rather than flip as the target crosses dead astern.
        val short = Mth.wrapDegrees(wantT - t.at)
        gun.way = when {
            abs(short) < SHORT_WAY_DEGREES || riders.isEmpty() && abs(short) <= 180.0 - KEEP_WAY_DEGREES -> if (short >= 0.0) 1 else -1
            gun.way != 0 && abs(short) > 180.0 - KEEP_WAY_DEGREES -> gun.way
            riders.isEmpty() -> if (short >= 0.0) 1 else -1
            else -> DeckClearance.swingWay(t.at, wantT, e.at, mount.traverseTop, mount.elevateTop, floorAt)
        }
        var gap = short
        if (gun.way > 0 && gap < 0.0) gap += 360.0
        if (gun.way < 0 && gap > 0.0) gap -= 360.0
        // Rising for the next stretch of the swing before it gets there.
        val reach = max(abs(t.rate) * 10.0, 15.0)
        val ahead = gap.coerceIn(-reach, reach)
        var lift = floorAt(t.at)
        for (i in 1..FLOOR_SAMPLES) lift = max(lift, floorAt(t.at + ahead * i / FLOOR_SAMPLES))
        val eGoal = max(wantE, if (abs(gap) > 2.0) lift else floorAt(wantT)).coerceIn(spec.lowest, spec.highest)
        e.step(eGoal - e.at, mount.elevateTop, mount.elevateAccel, if (eGoal == wantE) gun.followElevation.coerceIn(-mount.elevateTop, mount.elevateTop) else 0.0)
        val before = t.at
        val rateBefore = t.rate
        val top = if (watch != null) mount.traverseTop * WATCH_PACE else mount.traverseTop
        t.step(gap, top, mount.traverseAccel, gun.followTraverse.coerceIn(-mount.traverseTop, mount.traverseTop))
        if (e.at < floorAt(t.at) - 0.5) {
            // Not yet risen far enough to pass over a rider: it waits, and goes on as the barrel clears.
            t.at = before
            t.rate = (rateBefore * 0.5).let { if (abs(it) < mount.traverseAccel) 0.0 else it }
        }
    }

    private const val FLOOR_SAMPLES = 5

    // Firing.

    /**
     * One round down the barrel as it lies, carried on by the vehicle's velocity. A gunner's is theirs, outlined so it can
     * be followed up and down its arc; an unmanned gun's is a guard's, hurting only hostile mobs and sparing every block.
     */
    private fun fireRound(level: ServerLevel, vehicle: ArmedVehicle, gun: Gunnery, gunner: ServerPlayer?) {
        val round = vehicle.turretSpec.round
        val dir = Aiming.barrel(vehicle)
        val muzzle = Aiming.pivot(vehicle).add(dir.scale(vehicle.turretSpec.barrel))
        val shell = ShellEntity(VehicleGuns.SHELL, level)
        shell.owner = gunner ?: vehicle.entity
        shell.arm(round, vehicle.targetRules, guard = gunner == null)
        shell.item = ItemStack(round.item)
        if (gunner != null) shell.setGlowingTag(true)
        shell.setPos(muzzle)
        shell.deltaMovement = dir.scale(round.speed).add(gun.vehicleVelocity)
        level.addFreshEntity(shell)
        vehicle.turret.shotsFired++
        // A heavy gun's report: the crack of the charge, the boom under it, the breech's clank.
        val r = level.random
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 3.0f, 0.5f + r.nextFloat() * 0.08f)
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 3.0f, 0.55f + r.nextFloat() * 0.08f)
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.DISPENSER_LAUNCH, SoundSource.NEUTRAL, 1.0f, 0.5f)
    }
}
