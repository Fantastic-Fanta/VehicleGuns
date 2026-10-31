package dev.vehicleguns.client

import dev.vehicleguns.menu.MagazineMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

/** The magazine, drawn as a one-row chest: the vanilla container texture's top row and its inventory under it. */
class MagazineScreen(menu: MagazineMenu, inventory: Inventory, title: Component) : AbstractContainerScreen<MagazineMenu>(menu, inventory, title) {
    init {
        imageHeight = MagazineMenu.HEIGHT
        inventoryLabelY = imageHeight - 94
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // As a chest's screen draws a short chest: the top with as many rows as it has, then the inventory part.
        graphics.blit(CHEST, leftPos, topPos, 0, 0, imageWidth, TOP)
        graphics.blit(CHEST, leftPos, topPos + TOP, 0, 126, imageWidth, 96)
    }

    private companion object {
        /** One row of slots and the title over it, as ContainerScreen cuts it. */
        const val TOP = 18 + 17
        val CHEST: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
    }
}
