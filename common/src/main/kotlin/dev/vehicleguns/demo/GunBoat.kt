package dev.vehicleguns.demo

import dev.vehicleguns.VehicleGuns
import dev.vehicleguns.api.ArmedVehicle
import dev.vehicleguns.api.CameraSpec
import dev.vehicleguns.api.GuardSpec
import dev.vehicleguns.api.MachineGunSpec
import dev.vehicleguns.api.Magazine
import dev.vehicleguns.api.RoundSpec
import dev.vehicleguns.api.Turret
import dev.vehicleguns.api.TurretSpec
import dev.vehicleguns.api.TurretSync
import dev.vehicleguns.gun.Turrets
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.HasCustomInventoryScreen
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * The reference vehicle: a boat with a gun on its bow and a machine gun either side. Alone aboard, a player both
 * steers it and lays the guns; with two aboard, whoever sits behind is the gunner. With no player aboard the guns guard
 * on their own. The main gun fires fire charges from the boat's magazine, except in creative: the gunner opens it with
 * their inventory key, anyone with a sneak-click and an empty hand, and a sneak-click with fire charges loads them.
 */
class GunBoat(type: EntityType<out GunBoat>, level: Level) : Boat(type, level), ArmedVehicle, HasCustomInventoryScreen {
    override val turretSpec get() = SPEC
    override val turret = Turret(this, SYNC)
    override val magazine = Magazine(this) { it.`is`(Items.FIRE_CHARGE) }

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

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        if (!player.isSecondaryUseActive) return super.interact(player, hand)
        val stack = player.getItemInHand(hand)
        when {
            magazine.ammo(stack) -> if (!level().isClientSide) magazine.load(stack)
            stack.isEmpty -> if (!level().isClientSide) magazine.open(player)
            else -> return InteractionResult.PASS
        }
        return InteractionResult.sidedSuccess(level().isClientSide)
    }

    override fun openCustomInventoryScreen(player: Player) {
        if (player === gunner && !level().isClientSide) magazine.open(player)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        magazine.save(tag, registryAccess())
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        magazine.load(tag, registryAccess())
    }

    override fun destroy(source: DamageSource) {
        super.destroy(source)
        if (!level().isClientSide) magazine.dropAll()
    }

    override fun getDropItem(): Item = VehicleGuns.GUN_BOAT_ITEM

    companion object {
        val SYNC = TurretSync.define(GunBoat::class.java)

        private val SIDE_GUN = MachineGunSpec(mount = Vec3(0.1, 0.55, 0.45), rest = Vec3(0.5, 1.0, 0.0), barrel = 0.5)

        val SPEC = TurretSpec(
            pivotForward = 0.55,
            pivotUp = 0.75,
            barrel = 1.1,
            barrelRadius = 0.1,
            lowest = -5.0,
            round = RoundSpec(blastPower = 2.5f, reach = 80.0),
            guard = GuardSpec(range = 24.0),
            camera = CameraSpec(back = 7.0, up = 1.0, rise = 1.8),
            machineGuns = listOf(SIDE_GUN.mirrored(), SIDE_GUN),
        )
    }
}
