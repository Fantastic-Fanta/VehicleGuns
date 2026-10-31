package dev.vehicleguns.demo

import dev.vehicleguns.VehicleGuns
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.HitResult

/** Places a [GunBoat] where the player looks, on water or land, as a boat item does. */
class GunBoatItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY)
        if (hit.type == HitResult.Type.MISS) return InteractionResultHolder.pass(stack)
        val boat = GunBoat(VehicleGuns.GUN_BOAT, level)
        boat.setPos(hit.location)
        boat.yRot = player.yRot
        if (!level.noCollision(boat, boat.boundingBox)) return InteractionResultHolder.fail(stack)
        if (!level.isClientSide) {
            level.addFreshEntity(boat)
            level.gameEvent(player, GameEvent.ENTITY_PLACE, hit.location)
            stack.consume(1, player)
        }
        player.awardStat(Stats.ITEM_USED.get(this))
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }
}
