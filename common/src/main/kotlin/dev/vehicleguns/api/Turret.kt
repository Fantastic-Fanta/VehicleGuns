package dev.vehicleguns.api

import dev.vehicleguns.gun.Gunnery
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity

/**
 * The data a turret syncs to clients, defined once per vehicle class: `TurretSync.define(MyVehicle::class.java)` in
 * its companion, and [define] from its `defineSynchedData`.
 */
class TurretSync private constructor(
    internal val traverse: EntityDataAccessor<Float>,
    internal val elevation: EntityDataAccessor<Float>,
    internal val shots: EntityDataAccessor<Int>,
    internal val state: EntityDataAccessor<Byte>,
    internal val rounds: EntityDataAccessor<Int>,
) {
    fun define(builder: SynchedEntityData.Builder) {
        builder.define(traverse, 0f)
        builder.define(elevation, 0f)
        builder.define(shots, 0)
        builder.define(state, 0)
        builder.define(rounds, -1)
    }

    companion object {
        fun define(vehicle: Class<out Entity>) = TurretSync(
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.INT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.BYTE),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.INT),
        )
    }
}

/**
 * A vehicle's gun as it stands: where the turret points, how many rounds it has fired, and what its gunner's sight
 * should show. The server sets these; clients read them, and draw the turret at [traverse] and [elevation].
 * The server's laying state lives here too, so nothing outlives the vehicle.
 */
class Turret(private val vehicle: Entity, private val sync: TurretSync) {
    /** Degrees from the vehicle's heading, positive to its right. */
    var traverse: Float
        get() = vehicle.entityData.get(sync.traverse)
        internal set(value) = vehicle.entityData.set(sync.traverse, value)

    /** Degrees over the horizontal. */
    var elevation: Float
        get() = vehicle.entityData.get(sync.elevation)
        internal set(value) = vehicle.entityData.set(sync.elevation, value)

    /** Rounds fired so far: a client sees the gun fire as this changes. */
    var shotsFired: Int
        get() = vehicle.entityData.get(sync.shots)
        internal set(value) = vehicle.entityData.set(sync.shots, value)

    /** Rounds the gunner has left as last told, -1 for no limit. */
    var roundsShown: Int
        get() = vehicle.entityData.get(sync.rounds)
        internal set(value) { if (value != roundsShown) vehicle.entityData.set(sync.rounds, value) }

    private var state: Int
        get() = vehicle.entityData.get(sync.state).toInt()
        set(value) { if (value != state) vehicle.entityData.set(sync.state, value.toByte()) }

    /** Under a gunner: the gun lies on its lay. */
    var laid: Boolean
        get() = state and LAID != 0
        internal set(value) { state = if (value) state or LAID else state and LAID.inv() }

    /** Under a gunner: a trigger pull is waiting for the gun to load and lay. */
    var pullWaiting: Boolean
        get() = state and PULL_WAITING != 0
        internal set(value) { state = if (value) state or PULL_WAITING else state and PULL_WAITING.inv() }

    private var traverseO = 0f
    private var elevationO = 0f

    /** [traverse] eased between ticks, for drawing. */
    fun traverse(partial: Float): Float = traverseO + Mth.wrapDegrees(traverse - traverseO) * partial

    /** [elevation] eased between ticks, for drawing. */
    fun elevation(partial: Float): Float = Mth.lerp(partial, elevationO, elevation)

    internal fun keepPrevious() {
        traverseO = traverse
        elevationO = elevation
    }

    /** The server's laying state. */
    internal val gunnery = Gunnery()

    private companion object {
        const val LAID = 1
        const val PULL_WAITING = 2
    }
}
