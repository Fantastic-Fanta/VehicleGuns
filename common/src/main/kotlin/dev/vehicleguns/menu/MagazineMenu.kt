package dev.vehicleguns.menu

import dev.vehicleguns.VehicleGuns
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * A vehicle's magazine ([dev.vehicleguns.api.Magazine]) over the player's inventory: one row that takes the gun's
 * rounds alone. A shift-click moves rounds into the magazine, and anything back out to the player.
 */
class MagazineMenu(id: Int, inventory: Inventory, private val magazine: Container) : AbstractContainerMenu(VehicleGuns.MAGAZINE_MENU, id) {
    /** The client's side, before the server's contents arrive. */
    constructor(id: Int, inventory: Inventory) : this(id, inventory, SimpleContainer(ROW))

    init {
        checkContainerSize(magazine, ROW)
        magazine.startOpen(inventory.player)
        for (i in 0 until ROW) addSlot(object : Slot(magazine, i, 8 + i * 18, MAGAZINE_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = magazine.canPlaceItem(i, stack)
        })
        for (row in 0 until 3) for (col in 0 until 9) addSlot(Slot(inventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18))
        for (col in 0 until 9) addSlot(Slot(inventory, col, 8 + col * 18, INVENTORY_Y + 58))
    }

    override fun stillValid(player: Player): Boolean = magazine.stillValid(player)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index)?.takeIf { it.hasItem() } ?: return ItemStack.EMPTY
        val stack = slot.item
        val was = stack.copy()
        val moved = if (index < ROW) moveItemStackTo(stack, ROW, slots.size, true)
            else slots[0].mayPlace(stack) && moveItemStackTo(stack, 0, ROW, false)
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return was
    }

    override fun removed(player: Player) {
        super.removed(player)
        magazine.stopOpen(player)
    }

    companion object {
        const val ROW = 9
        const val MAGAZINE_Y = 18
        const val INVENTORY_Y = 49
        const val HEIGHT = 132
    }
}
