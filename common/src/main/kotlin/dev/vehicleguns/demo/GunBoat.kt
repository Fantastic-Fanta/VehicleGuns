package dev.vehicleguns.demo

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.CameraSpec
import dev.vehicleguns.api.GuardSpec
import dev.vehicleguns.api.RoundSpec
import dev.vehicleguns.api.Turret
import dev.vehicleguns.api.TurretSpec
import dev.vehicleguns.api.TurretSync
import dev.vehicleguns.gun.Turrets
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level

/**
 * The reference vehicle: a boat with a gun on its bow. Alone aboard, a player both steers it and lays the gun; with
 * two aboard, whoever sits behind is the gunner. With no player aboard the gun guards on its own. A gunner's rounds
 * are fire charges from their inventory, except in creative.
 */
class GunBoat(type: EntityType<out GunBoat>, level: Level) : Boat(type, level), ArmedVehicle {
    override val turretSpec get() = SPEC
    override val turret = Turret(this, SYNC)

    override val gunner: Player? get() = passengers.lastOrNull { it is Player } as Player?

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        SYNC.define(builder)
    }

    override fun tick() {
        super.tick()
        Turrets.tick(this)
    }

    /** The gunner looks all round; a boat would hold its riders' view within a half turn of ahead. */
    override fun clampRotation(rider: Entity) {
        if (rider !== gunner) super.clampRotation(rider)
    }

    override fun roundsLeft(gunner: Player): Int = if (gunner.abilities.instabuild) -1 else gunner.inventory.countItem(AMMO)

    override fun takeRound(gunner: Player): Boolean {
        if (gunner.abilities.instabuild) return true
        val slot = gunner.inventory.findSlotMatchingItem(ItemStack(AMMO))
        if (slot < 0) return false
        gunner.inventory.removeItem(slot, 1)
        return true
    }

    override fun getDropItem(): Item = VehicleGuns.GUN_BOAT_ITEM

    companion object {
        val SYNC = TurretSync.define(GunBoat::class.java)
        private val AMMO = Items.FIRE_CHARGE

        val SPEC = TurretSpec(
            pivotForward = 0.55,
            pivotUp = 0.75,
            barrel = 1.1,
            barrelRadius = 0.1,
            lowest = -5.0,
            round = RoundSpec(blastPower = 2.5f, reach = 80.0),
            guard = GuardSpec(range = 24.0),
            camera = CameraSpec(back = 7.0, up = 1.0, rise = 1.8),
        )
    }
}
