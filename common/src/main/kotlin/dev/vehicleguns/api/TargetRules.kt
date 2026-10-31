package dev.vehicleguns.api

import dev.vehicleguns.GunSettings
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.animal.IronGolem
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.npc.Npc
import net.minecraft.world.entity.player.Player

/** Who a vehicle's gun may shoot. Override per vehicle ([ArmedVehicle.targetRules]) to add your own exceptions. */
interface TargetRules {
    /**
     * Whether the gun guarding on its own may hit [entity]. By default hostile mobs only: never a pet, a named mob, a
     * neutral mob that is not angry, or anyone riding an armed vehicle.
     */
    fun guardMayHit(entity: Entity): Boolean =
        entity is Mob && entity is Enemy && entity.isAlive && !entity.isInvulnerable &&
            !(entity is NeutralMob && !entity.isAngry) && !entity.hasCustomName() &&
            !(entity is OwnableEntity && entity.ownerUUID != null) && entity.rootVehicle !is ArmedVehicle

    /** Whether a gunner's round may hurt [entity]: anything alive, and players when the server allows it. */
    fun gunnerMayHit(entity: Entity): Boolean =
        entity is LivingEntity && entity.isAlive && !entity.isInvulnerable && !entity.isSpectator &&
            (entity !is Player || GunSettings.gunnerHurtsPlayers)

    /**
     * Someone a guarding gun never fires past, though its round would pass through them: players, villagers, golems,
     * pets and anyone riding an armed vehicle. Wild animals are not, or a field of cows would keep it from firing.
     */
    fun friendly(entity: Entity): Boolean {
        if (entity !is LivingEntity || !entity.isAlive || entity.isSpectator || guardMayHit(entity)) return false
        return entity is Player || entity is Npc || entity is IronGolem ||
            (entity is OwnableEntity && entity.ownerUUID != null) || entity.rootVehicle is ArmedVehicle
    }

    companion object {
        val DEFAULT: TargetRules = object : TargetRules {}
    }
}
