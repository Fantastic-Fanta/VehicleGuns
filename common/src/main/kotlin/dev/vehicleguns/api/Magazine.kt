package dev.vehicleguns.api

import dev.vehicleguns.menu.MagazineMenu
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * A vehicle's store of rounds for its main gun: one row of slots that take [ammo] alone. The gunner opens it with
 * their inventory key; the vehicle saves it with [save] and [load] and drops it with [dropAll].
 */
class Magazine(private val vehicle: Entity, val ammo: (ItemStack) -> Boolean) : SimpleContainer(SLOTS) {
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = ammo(stack)

    override fun stillValid(player: Player): Boolean =
        vehicle.isAlive && (player.vehicle === vehicle || player.distanceToSqr(vehicle) < 64.0)

    /** Rounds in it. */
    fun rounds(): Int = (0 until containerSize).sumOf { getItem(it).takeIf(ammo)?.count ?: 0 }

    /** Takes one round, from the last slot first; false when it is empty. */
    fun take(): Boolean {
        for (slot in containerSize - 1 downTo 0) {
            if (ammo(getItem(slot))) {
                removeItem(slot, 1)
                return true
            }
        }
        return false
    }

    /** Loads what it can of [stack]; what does not fit stays in it. */
    fun load(stack: ItemStack) {
        if (!ammo(stack)) return
        stack.count = addItem(stack.copy()).count
    }

    fun open(player: Player) {
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> MagazineMenu(id, inventory, this) }, TITLE))
    }

    fun save(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.put(KEY, createTag(registries))
    }

    fun load(tag: CompoundTag, registries: HolderLookup.Provider) {
        if (tag.contains(KEY)) fromTag(tag.getList(KEY, 10), registries)
    }

    fun dropAll() {
        net.minecraft.world.Containers.dropContents(vehicle.level(), vehicle, this)
    }

    companion object {
        const val SLOTS = 9
        private const val KEY = "Magazine"
        private val TITLE: Component = Component.translatable("container.vehicleguns.magazine")
    }
}
