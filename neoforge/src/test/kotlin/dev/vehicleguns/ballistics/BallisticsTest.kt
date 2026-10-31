package dev.vehicleguns.ballistics

import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BallisticsTest {
    private val speed = 2.2
    private val gravity = 0.02
    private val lowest = -5.0

    /** How close a round fired along [dir] from [pivot] comes to a target at [target] going [velocity] a tick. */
    private fun closest(pivot: Vec3, dir: Vec3, target: Vec3, velocity: Vec3 = Vec3.ZERO, vehicle: Vec3 = Vec3.ZERO): Double {
        var best = Double.MAX_VALUE
        var was = pivot.add(dir.scale(1.0))
        Ballistics.fly(was, dir.scale(speed).add(vehicle), gravity, 30) { t, at ->
            best = minOf(best, Ballistics.closestOff(was, at, target.add(velocity.scale(t - 1.0)), target.add(velocity.scale(t.toDouble()))).length())
            was = at
            true
        }
        return best
    }

    @Test
    fun anglesAndDirectionAgree() {
        for (yaw in listOf(0.0, 37.0, -120.0, 179.0)) for (t in listOf(-170.0, -45.0, 0.0, 90.0, 135.0)) for (e in listOf(-15.0, 0.0, 30.0, 80.0)) {
            val (t2, e2) = Ballistics.angles(yaw, Ballistics.direction(yaw, t, e))
            assertEquals(t, t2, 1e-6, "traverse at yaw $yaw")
            assertEquals(e, e2, 1e-6, "elevation at yaw $yaw")
        }
        // Yaw 0 faces +z; a right turn of 90 faces -x, as Minecraft's yaw goes.
        assertEquals(-1.0, Ballistics.direction(0.0, 90.0, 0.0).x, 1e-9)
    }

    @Test
    fun aRoundLaidOnAStillTargetMeetsIt() {
        val pivot = Vec3(0.0, 65.0, 0.0)
        for (target in listOf(Vec3(20.0, 64.0, 3.0), Vec3(-5.0, 63.0, -8.0), Vec3(2.0, 85.0, 1.0), Vec3(0.0, 64.0, -22.0))) {
            val s = Ballistics.solve(pivot, 1.0, target, Vec3.ZERO, Vec3.ZERO, speed, gravity, 18)
            assertNotNull(s, "no solution for $target")
            assertTrue(closest(pivot, s!!.dir, target) < Ballistics.CLOSE_ENOUGH, "missed $target by ${closest(pivot, s.dir, target)}")
        }
    }

    @Test
    fun itLeadsAMovingTargetAndAllowsForItsOwnVelocity() {
        val pivot = Vec3(0.0, 65.0, 0.0)
        val target = Vec3(18.0, 64.0, 0.0)
        val running = Vec3(0.0, 0.0, 0.25)
        val vehicle = Vec3(0.3, 0.0, 0.0)
        val s = Ballistics.solve(pivot, 1.0, target, running, vehicle, speed, gravity, 18)!!
        assertTrue(closest(pivot, s.dir, target, running, vehicle) < Ballistics.CLOSE_ENOUGH, "missed the running target")
        // Straight at it would miss: the lead is real.
        assertTrue(closest(pivot, target.subtract(pivot).normalize(), target, running, vehicle) > 1.0)
    }

    @Test
    fun noSolutionOutOfReach() {
        assertNull(Ballistics.solve(Vec3.ZERO, 1.0, Vec3(80.0, 0.0, 0.0), Vec3.ZERO, Vec3.ZERO, speed, gravity, 18))
    }

    @Test
    fun theBarrelRisesOverARiderBehindIt() {
        val pivot = Vec3(0.0, 2.3, 0.0)
        // Facing +z; a rider standing 0.6 behind the pivot.
        val rider = DeckClearance.Rider(Vec3(0.0, 1.9, -0.6), 1.9, 0.35)
        assertEquals(lowest, DeckClearance.floor(0.0, 0.0, pivot, 1.0, 0.15, listOf(rider), lowest), 1e-9, "nothing ahead of the gun")
        val behind = DeckClearance.floor(0.0, 180.0, pivot, 1.0, 0.15, listOf(rider), lowest)
        assertTrue(behind > 60.0, "over the rider behind it the gun stands up, at $behind")
        assertEquals(lowest, DeckClearance.floor(0.0, 180.0, pivot, 1.0, 0.15, emptyList(), lowest), 1e-9)
    }

    @Test
    fun aMountGathersSpeedAndStopsOnTheBearingWithoutOvershooting() {
        val axis = MountAxis()
        var lastRate = 0.0
        var peak = 0.0
        repeat(200) {
            axis.step(150.0 - axis.at, 4.5, 0.45)
            // Settling on the bearing it may lose up to twice its braking in its last tick.
            assertTrue(abs(axis.rate - lastRate) <= 0.45 * 2.0 + 1e-9, "a jerk of ${axis.rate - lastRate}")
            assertTrue(axis.at <= 150.0 + 1e-9, "overshot to ${axis.at}")
            peak = maxOf(peak, axis.rate)
            lastRate = axis.rate
        }
        assertEquals(150.0, axis.at, 1e-9)
        assertEquals(0.0, axis.rate, 1e-9)
        assertEquals(4.5, peak, 1e-9)
    }

    @Test
    fun itSwingsTheLongWayRatherThanStandTheBarrelUpOverTheRiders() {
        // From 90 right round to 100 left: the short way passes astern, over riders that need it at 80 degrees.
        val overRiders = { t: Double -> if (abs(Mth.wrapDegrees(t - 180.0)) < 40.0) 80.0 else lowest }
        assertEquals(-1, DeckClearance.swingWay(90.0, -100.0, 0.0, 4.5, 3.0, overRiders), "round by the bow")
        // With no one aboard it takes the short way, astern.
        assertEquals(1, DeckClearance.swingWay(90.0, -100.0, 0.0, 4.5, 3.0) { lowest })
    }

    @Test
    fun aMountHoldsOnATargetCrossingItsSights() {
        // Its bearing sweeps 5 degrees a tick, starting 60 degrees off the gun.
        val axis = MountAxis()
        var goal = 60.0
        var last = goal
        var follow = 0.0
        var worst = 0.0
        repeat(80) { tick ->
            goal += 5.0
            follow = (follow + (goal - last)) * 0.5
            last = goal
            axis.step(goal - axis.at, 9.0, 1.2, follow)
            if (tick >= 30) worst = maxOf(worst, abs(goal - axis.at))
        }
        assertTrue(worst < 0.5, "trailing its target by up to $worst degrees once caught up")
    }

    @Test
    fun aTrackerSettlesOnAWalkerAndTellsWhereItWillBe() {
        val walk = Vec3(0.08, 0.0, -0.05)
        val track = TargetTrack(Vec3(10.0, 64.0, 5.0))
        var at = Vec3(10.0, 64.0, 5.0)
        repeat(30) { at = at.add(walk); track.see(at) }
        assertEquals(0.0, track.velocity.subtract(walk).length(), 1e-3, "speed")
        val then = at.add(walk.scale(11.0))
        assertTrue(track.ahead(11.0).distanceTo(then) < 0.02, "off by ${track.ahead(11.0).distanceTo(then)}")
    }

    @Test
    fun aTrackerSmoothsAStopStartShuffle() {
        // 0.1 a tick on average, but in lurches: two ticks at 0.2, two standing.
        val track = TargetTrack(Vec3.ZERO)
        var at = Vec3.ZERO
        var worst = 0.0
        for (t in 0 until 80) {
            at = at.add(if (t % 4 < 2) 0.2 else 0.0, 0.0, 0.0)
            track.see(at)
            if (t > 20) worst = maxOf(worst, abs(track.velocity.x - 0.1))
        }
        assertTrue(worst < 0.08, "tracked speed swings by $worst")
    }

    @Test
    fun aShotMeetsABoxOnlyWhenItsLineRunsThrough() {
        val box = AABB(9.7, 63.0, -0.3, 10.3, 65.0, 0.3)
        val from = Vec3(0.0, 64.0, 0.0)
        val hit = Ballistics.meets(from, Vec3(1.0, 0.0, 0.0), 20.0, box, 0.0)
        assertNotNull(hit)
        assertEquals(9.7, hit!!.x, 1e-9)
        assertNull(Ballistics.meets(from, Vec3(1.0, 0.0, 0.1).normalize(), 20.0, box, 0.0), "a few degrees off passes it by")
        assertNull(Ballistics.meets(from, Vec3(1.0, 0.0, 0.0), 5.0, box, 0.0), "short of it")
    }

    @Test
    fun theVehicleFrameTurnsWithTheHeadingAndBack() {
        // Heading 0 faces +z, so ahead is +z and its right is -x.
        assertEquals(0.0, VehicleFrame.way(0.0, Vec3(1.0, 0.0, 0.0)).distanceTo(Vec3(0.0, 0.0, 1.0)), 1e-9)
        assertEquals(0.0, VehicleFrame.way(0.0, Vec3(0.0, 1.0, 0.0)).distanceTo(Vec3(-1.0, 0.0, 0.0)), 1e-9)
        for (heading in listOf(0.0, 37.0, -120.0, 179.0)) {
            val local = Vec3(0.3, -0.7, 0.2)
            assertEquals(0.0, VehicleFrame.local(heading, VehicleFrame.way(heading, local)).distanceTo(local), 1e-9, "round trip at $heading")
            // Ahead in the vehicle's frame is the heading's own forward.
            assertEquals(0.0, VehicleFrame.way(heading, Vec3(1.0, 0.0, 0.0)).distanceTo(Ballistics.forward(heading)), 1e-9)
        }
    }

    @Test
    fun aSmallMountTurnsAtItsPaceAndStopsOnItsMark() {
        val from = Vec3(0.0, 0.0, 1.0)
        val to = Vec3(1.0, 0.0, 0.0)
        val step = VehicleFrame.turnToward(from, to, 12.0)
        assertEquals(12.0, Ballistics.apart(from, step), 1e-6, "a tick's turn")
        assertEquals(78.0, Ballistics.apart(step, to), 1e-6, "the rest still to go")
        var at = from
        repeat(8) { at = VehicleFrame.turnToward(at, to, 12.0) }
        assertEquals(0.0, Ballistics.apart(at, to), 1e-6, "on its mark after 90 degrees at 12 a tick")
        // Asked to turn straight about, it still turns a tick's worth.
        assertEquals(12.0, Ballistics.apart(from, VehicleFrame.turnToward(from, from.scale(-1.0), 12.0)), 1e-6)
    }

    /** Flies a round as the gun fires it and returns where it comes down through [floorY]. */
    private fun landingOf(pivot: Vec3, lay: Ballistics.Arc, vehicle: Vec3, floorY: Double): Vec3 {
        var at = pivot.add(lay.dir.scale(1.91))
        var v = lay.dir.scale(1.9).add(vehicle)
        var was = at
        for (t in 1..200) {
            was = at
            at = at.add(v)
            v = v.scale(Ballistics.AIR_DRAG).add(0.0, -0.045, 0.0)
            if (at.y < floorY && v.y < 0) break
        }
        return was.add(at.subtract(was).scale((was.y - floorY) / (was.y - at.y)))
    }

    /** From a vehicle under way across its line of fire the round still comes down on the spot, flat arc and lob alike. */
    @Test
    fun anArcFromAVehicleUnderWayAcrossItsLineComesDownOnTheSpot() {
        val pivot = Vec3(0.0, 64.8, 0.0)
        val target = Vec3(0.0, 63.0, 40.0)
        for (flat in listOf(false, true)) for (way in listOf(Vec3(0.5, 0.0, 0.0), Vec3(-0.4, 0.0, 0.2), Vec3(0.3, 0.0, -0.3), Vec3.ZERO)) {
            val lay = Ballistics.arcUnderWay(pivot, 1.91, target, way, 1.9, 0.045, 200, if (flat) -8.0 else 30.0, 84.0, Vec3(0.0, 0.0, 1.0), flat)
            if (flat) assertTrue(lay.dir.y < Math.sin(Math.toRadians(30.0)), "the flat arc is laid flat: $lay")
            assertTrue(lay.reaches, "a spot 40 out making $way is in reach: $lay")
            val down = landingOf(pivot, lay, way, target.y)
            val off = Math.hypot(down.x - target.x, down.z - target.z)
            assertTrue(off < 0.5, "making $way the round came down ${"%.2f".format(off)} off, at $down")
            assertTrue(lay.lands.distanceTo(down) < 0.6, "the lay says it lands at ${lay.lands}, it came down at $down")
        }
    }

    /** A spot up a bank, higher than a flat arc ever climbs at its flattest, is laid on all the same. */
    @Test
    fun anArcLaysOnASpotUpABank() {
        val pivot = Vec3(0.0, 64.8, 0.0)
        for (target in listOf(Vec3(0.0, 80.0, 20.0), Vec3(0.0, 82.0, 28.0), Vec3(0.0, 72.0, 12.0))) for (flat in listOf(false, true)) {
            val lay = Ballistics.arcUnderWay(pivot, 1.91, target, Vec3.ZERO, 1.9, 0.045, 200, if (flat) -8.0 else 30.0, 84.0, Vec3(0.0, 0.0, 1.0), flat)
            assertTrue(lay.reaches && !lay.near, "a spot at $target is in reach (flat $flat): $lay")
            var at = pivot.add(lay.dir.scale(1.91))
            var v = lay.dir.scale(1.9)
            var nearest = Double.MAX_VALUE
            for (t in 1..200) {
                val next = at.add(v)
                for (k in 0..20) nearest = minOf(nearest, at.add(next.subtract(at).scale(k / 20.0)).distanceTo(target))
                at = next
                v = v.scale(Ballistics.AIR_DRAG).add(0.0, -0.045, 0.0)
            }
            assertTrue(nearest < 0.5, "laid on $target (flat $flat) it passed ${"%.2f".format(nearest)} off")
            if (flat) assertTrue(lay.dir.y < Math.sin(Math.toRadians(60.0)), "the flat arc is not stood up: $lay")
            assertTrue(lay.lands.distanceTo(target) < 0.5, "the lay says it meets the spot at ${lay.lands}")
        }
        // Too high for any arc: out of reach, not too near.
        val sky = Ballistics.arcUnderWay(pivot, 1.91, Vec3(0.0, 200.0, 20.0), Vec3.ZERO, 1.9, 0.045, 200, -8.0, 84.0, Vec3(0.0, 0.0, 1.0), true)
        assertTrue(!sky.reaches && !sky.near, "a spot 135 blocks up is out of reach: $sky")
    }

    /** Remembering the sweep across the passes of a lay under way gives the same lay as working each pass out afresh. */
    @Test
    fun theRememberedSweepChangesNothing() {
        val pivot = Vec3(0.0, 64.8, 0.0)
        val way = Vec3(0.4, 0.0, 0.1)
        val level = Vec3(way.x, 0.0, way.z)
        for (target in listOf(Vec3(5.0, 63.0, 40.0), Vec3(-30.0, 70.0, 12.0), Vec3(0.0, 60.0, 75.0))) for (flat in listOf(false, true)) {
            val low = if (flat) -8.0 else 30.0
            val remembered = Ballistics.arcUnderWay(pivot, 1.91, target, way, 1.9, 0.045, 200, low, 84.0, Vec3(0.0, 0.0, 1.0), flat)
            var afresh = Ballistics.arc(pivot, 1.91, target, Vec3.ZERO, 1.9, 0.045, 200, low, 84.0, Vec3(0.0, 0.0, 1.0), flat)
            repeat(3) { afresh = Ballistics.arc(pivot, 1.91, target.subtract(level.scale(Ballistics.carried(afresh.ticks))), Vec3.ZERO, 1.9, 0.045, 200, low, 84.0, Vec3(0.0, 0.0, 1.0), flat) }
            assertEquals(0.0, remembered.dir.distanceTo(afresh.dir), 1e-12, "the same lay on $target (flat $flat)")
        }
    }
}
