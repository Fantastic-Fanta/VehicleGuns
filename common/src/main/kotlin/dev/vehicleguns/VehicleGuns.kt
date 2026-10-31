package dev.vehicleguns

import dev.vehicleguns.demo.GunBoat
import dev.vehicleguns.demo.GunBoatItem
import dev.vehicleguns.entity.ShellEntity
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.Item

/** The mod's id and what it registers. Each loader calls [registerEntityTypes] and [registerItems] at its own time. */
object VehicleGuns {
    const val MOD_ID = "vehicleguns"

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    val SHELL: EntityType<ShellEntity> by lazy {
        EntityType.Builder.of(::ShellEntity, MobCategory.MISC).sized(0.3f, 0.3f).clientTrackingRange(8).updateInterval(1).build("$MOD_ID:shell")
    }

    val SHELL_DAMAGE: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, id("shell"))

    /** The demo vehicle: a boat with a gun on it. */
    val GUN_BOAT: EntityType<GunBoat> by lazy {
        EntityType.Builder.of(::GunBoat, MobCategory.MISC).sized(1.375f, 0.5625f).clientTrackingRange(10).build("$MOD_ID:gun_boat")
    }

    val GUN_BOAT_ITEM: Item by lazy { GunBoatItem(Item.Properties().stacksTo(1)) }

    fun registerEntityTypes(register: (ResourceLocation, EntityType<*>) -> Unit) {
        register(id("shell"), SHELL)
        register(id("gun_boat"), GUN_BOAT)
    }

    fun registerItems(register: (ResourceLocation, Item) -> Unit) {
        register(id("gun_boat"), GUN_BOAT_ITEM)
    }
}
