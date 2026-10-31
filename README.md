# VehicleGuns

Turrets for any vehicle entity. Minecraft 1.21.1, NeoForge and Fabric, Kotlin.

Demos from [Lapras Ferry Lines](https://github.com/Fantastic-Fanta/LaprasFerry), where it started:
[Blastoise](https://gyazo.com/bfa89033004447d7f4f96d74b3f3dfc9) and [Lapras](https://gyazo.com/a979786342eb43d9c5dc383dbc41b148).

## Features

- Ballistic laying: target lead, vehicle velocity, flat or lobbed arcs
- Turret mount with acceleration, target pacing and rider clearance
- Gunner mode: third-person sight camera, zoom, lock-on, HUD
- Guard mode: auto-targets hostile mobs, no block damage
- Explosive shells with staged burst effects

## Usage

```kotlin
class Tank(type: EntityType<Tank>, level: Level) : Entity(type, level), ArmedVehicle {
    override val turretSpec = TurretSpec(pivotUp = 1.6, barrel = 2.2)
    override val turret = Turret(this, SYNC)
    override val gunner get() = firstPassenger as? Player

    override fun defineSynchedData(builder: SynchedEntityData.Builder) { SYNC.define(builder) }
    override fun tick() { super.tick(); Turrets.tick(this) }

    companion object { val SYNC = TurretSync.define(Tank::class.java) }
}
```

Draw the turret at `turret.traverse(partial)` and `turret.elevation(partial)`.

| Hook | Default |
| --- | --- |
| `roundsLeft` / `takeRound` | unlimited ammo |
| `targetRules` | guard: hostile mobs; gunner: any living thing |
| `Turrets.wantsSteady` | true while the gunner zooms or locks on |

`TurretSpec` configures geometry, mount speeds, rounds, guard and camera. `GunSettings` holds global toggles.

## Layout

| Path | Contents |
| --- | --- |
| `common/.../ballistics` | flight, lead, arcs, mount axes, tracking |
| `common/.../api` | `ArmedVehicle`, `Turret`, `TurretSpec`, `TargetRules` |
| `common/.../gun` | server gunnery, shared aiming |
| `common/.../entity` | shell |
| `common/.../client` | input, camera, sight, HUD, effects |
| `common/.../demo` | Gun Boat |
| `neoforge`, `fabric` | loader entry points |

## Build

```bash
./gradlew :neoforge:runClient
```

```bash
./gradlew :fabric:runFabricClient
```

```bash
./gradlew :neoforge:test
```

The demo Gun Boat is in the Tools & Utilities tab.

## License

MIT
