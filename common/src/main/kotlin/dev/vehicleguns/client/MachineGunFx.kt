package dev.vehicleguns.client

import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.entity
import dev.vehicleguns.ballistics.VehicleFrame

/** Each new machine-gun shot of a vehicle in view, as the server counts them: a tracer from its muzzle to where it went. */
internal object MachineGunFx {
    /** A burst seen late (the vehicle just came into view) draws no more than this many tracers at once. */
    private const val MOST_AT_ONCE = 3

    fun tick(vehicle: ArmedVehicle) {
        val specs = vehicle.turretSpec.machineGuns
        if (specs.isEmpty()) return
        val turret = vehicle.turret
        if (turret.shotsSeen.size != specs.size) {
            turret.shotsSeen = IntArray(specs.size) { turret.machineGunShots(it) }
            return
        }
        val e = vehicle.entity
        val heading = e.yRot.toDouble()
        for ((i, spec) in specs.withIndex()) {
            val fired = turret.machineGunShots(i)
            val was = turret.shotsSeen[i]
            turret.shotsSeen[i] = fired
            if (fired == was) continue
            val end = turret.machineGunEnd(i) ?: continue
            val dir = turret.machineGunDirection(i, spec.rest, heading, 1f)
            val muzzle = VehicleFrame.point(e.position(), heading, spec.mount).add(dir.scale(spec.barrel))
            repeat((fired - was).coerceIn(1, MOST_AT_ONCE)) { Tracers.fire(muzzle, end, spec.bulletSpeed) }
        }
    }
}
