package dev.vehicleguns.entity

import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/** A gun's blow. The death message names the gunner, or the vehicle when it has a name: `death.attack.<id>` and `.named`. */
class GunDamage(type: Holder<DamageType>, direct: Entity, private val firer: Entity?) : DamageSource(type, direct, firer) {
    override fun getLocalizedDeathMessage(killed: LivingEntity): Component {
        val key = "death.attack." + type().msgId()
        val name = (firer as? Player)?.displayName ?: firer?.customName
        return if (name != null && name.string.isNotBlank()) Component.translatable("$key.named", killed.displayName, name)
        else Component.translatable(key, killed.displayName)
    }

    companion object {
        fun source(level: ServerLevel, type: ResourceKey<DamageType>, direct: Entity, firer: Entity?): DamageSource =
            GunDamage(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), direct, firer)
    }
}
