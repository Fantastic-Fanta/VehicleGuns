package dev.vehicleguns.api

import dev.vehicleguns.ballistics.VehicleFrame
import dev.vehicleguns.gun.Gunnery
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

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
    internal val guns: EntityDataAccessor<CompoundTag>,
) {
    fun define(builder: SynchedEntityData.Builder) {
        builder.define(traverse, 0f)
        builder.define(elevation, 0f)
        builder.define(shots, 0)
        builder.define(state, 0)
        builder.define(rounds, -1)
        builder.define(guns, CompoundTag())
    }

    companion object {
        fun define(vehicle: Class<out Entity>) = TurretSync(
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.FLOAT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.INT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.BYTE),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.INT),
            SynchedEntityData.defineId(vehicle, EntityDataSerializers.COMPOUND_TAG),
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

    /**
     * The machine guns as the server last told: each one's rounds fired, where its last bullet went, and its lie in
     * the vehicle's frame; and how much firing by hand they have left, 1 full ([heat]), and whether they are cooling.
     */
    internal var machineGuns: CompoundTag
        get() = vehicle.entityData.get(sync.guns)
        set(value) = vehicle.entityData.set(sync.guns, value)

    fun machineGunShots(index: Int): Int = machineGuns.getIntArray(SHOTS).getOrElse(index) { 0 }

    /** Where machine gun [index]'s last bullet went, or null before it fired. */
    fun machineGunEnd(index: Int): Vec3? = machineGuns.takeIf { it.contains("$END_X$index") }?.let {
        Vec3(it.getDouble("$END_X$index"), it.getDouble("$END_Y$index"), it.getDouble("$END_Z$index"))
    }

    /** Machine gun [index]'s lie in the vehicle's frame (ahead, right, up), or its rest. */
    fun machineGunLie(index: Int, rest: Vec3): Vec3 = machineGuns.takeIf { it.contains("$LIE_X$index") }?.let {
        Vec3(it.getFloat("$LIE_X$index").toDouble(), it.getFloat("$LIE_Y$index").toDouble(), it.getFloat("$LIE_Z$index").toDouble())
    } ?: rest.normalize()

    /** Machine gun [index]'s direction in the world for a vehicle heading [heading], eased between ticks, for drawing. */
    fun machineGunDirection(index: Int, rest: Vec3, heading: Double, partial: Float): Vec3 {
        val now = machineGunLie(index, rest)
        val was = lieO.getOrNull(index) ?: now
        return VehicleFrame.way(heading, was.lerp(now, partial.toDouble()).normalize())
    }

    /** Share of the machine guns' firing by hand left, 1 full. */
    val heat: Float get() = if (machineGuns.contains(HEAT)) machineGuns.getFloat(HEAT) else 1f

    /** Spent, the machine guns cool until full before they fire by hand again. */
    val overheated: Boolean get() = machineGuns.getBoolean(COOLING)

    private var traverseO = 0f
    private var elevationO = 0f
    private var lieO: Array<Vec3?> = emptyArray()

    /** [traverse] eased between ticks, for drawing. */
    fun traverse(partial: Float): Float = traverseO + Mth.wrapDegrees(traverse - traverseO) * partial

    /** [elevation] eased between ticks, for drawing. */
    fun elevation(partial: Float): Float = Mth.lerp(partial, elevationO, elevation)

    internal fun keepPrevious(guns: Int, rest: (Int) -> Vec3) {
        traverseO = traverse
        elevationO = elevation
        if (lieO.size != guns) lieO = arrayOfNulls(guns)
        for (i in 0 until guns) lieO[i] = machineGunLie(i, rest(i))
    }

    /** Client: each machine gun's shots as last drawn, to tell new ones by. */
    internal var shotsSeen: IntArray = IntArray(0)

    /** The server's laying state. */
    internal val gunnery = Gunnery()

    internal companion object {
        const val LAID = 1
        const val PULL_WAITING = 2
        const val SHOTS = "s"
        const val END_X = "ex"
        const val END_Y = "ey"
        const val END_Z = "ez"
        const val LIE_X = "lx"
        const val LIE_Y = "ly"
        const val LIE_Z = "lz"
        const val HEAT = "h"
        const val COOLING = "c"
    }
}
