package dev.vehicleguns.gun

import dev.vehicleguns.api.Turret
import net.minecraft.nbt.CompoundTag

/** What clients draw of a vehicle's lesser weapons, written into its [Turret] when it changes. */
internal object WeaponSync {
    fun publish(turret: Turret, gun: Gunnery) {
        turret.machineGuns = CompoundTag().apply {
            putIntArray(Turret.SHOTS, IntArray(gun.machineGuns.size) { gun.machineGuns[it].shots })
            for ((i, mg) in gun.machineGuns.withIndex()) {
                mg.lastEnd?.let { putDouble("${Turret.END_X}$i", it.x); putDouble("${Turret.END_Y}$i", it.y); putDouble("${Turret.END_Z}$i", it.z) }
                mg.lie?.let { putFloat("${Turret.LIE_X}$i", it.x.toFloat()); putFloat("${Turret.LIE_Y}$i", it.y.toFloat()); putFloat("${Turret.LIE_Z}$i", it.z.toFloat()) }
            }
            putFloat(Turret.HEAT, gun.heat.toFloat())
            putBoolean(Turret.COOLING, gun.cooling)
            putFloat(Turret.STAMINA, gun.stamina.toFloat())
            putBoolean(Turret.SPENT, gun.spent)
        }
    }
}
