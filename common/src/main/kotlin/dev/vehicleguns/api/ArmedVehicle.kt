package dev.vehicleguns.api

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * An entity that carries a gun. Implement it on your vehicle's entity class, keep a [Turret] for it, and call
 * `Turrets.tick(this)` from its `tick()` on both sides. Everything else (laying, firing, the gunner's camera and sight)
 * the library does.
 *
 * ```
 * class Tank(type: EntityType<Tank>, level: Level) : Entity(type, level), ArmedVehicle {
 *     override val turretSpec = TurretSpec(pivotUp = 1.6, barrel = 2.2)
 *     override val turret = Turret(this, SYNC)
 *     override val gunner get() = firstPassenger as? Player
 *     override fun defineSynchedData(builder: SynchedEntityData.Builder) { SYNC.define(builder) }
 *     override fun tick() { super.tick(); Turrets.tick(this) }
 *     companion object { val SYNC = TurretSync.define(Tank::class.java) }
 * }
 * ```
 */
interface ArmedVehicle {
    val turretSpec: TurretSpec
    val turret: Turret

    /** Who lays the gun now, or null to let it guard on its own ([TurretSpec.guard]). */
    val gunner: Player?

    /** Who the gun may shoot. */
    val targetRules: TargetRules get() = TargetRules.DEFAULT

    /**
     * Where the main gun's rounds come from, opened with the gunner's inventory key; null for none, when rounds are
     * unlimited unless [roundsLeft] and [takeRound] say otherwise. The vehicle saves and drops it.
     */
    val magazine: Magazine? get() = null

    /** Rounds [gunner] has to fire, or -1 for no limit. Shown on their sight. */
    fun roundsLeft(gunner: Player): Int = if (gunner.abilities.instabuild) -1 else magazine?.rounds() ?: -1

    /** Takes a round for [gunner] to fire; false when there is none. A guarding gun needs none. */
    fun takeRound(gunner: Player): Boolean = gunner.abilities.instabuild || magazine?.take() ?: true
}

/** The entity an [ArmedVehicle] is. */
val ArmedVehicle.entity: Entity get() = this as Entity
