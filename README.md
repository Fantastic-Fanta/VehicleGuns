# VehicleGuns

Turrets for any vehicle entity. Minecraft 1.21.1, NeoForge and Fabric, Kotlin.

Demos: [Blastoise](https://gyazo.com/bfa89033004447d7f4f96d74b3f3dfc9), [Lapras](https://gyazo.com/a979786342eb43d9c5dc383dbc41b148).

## Features

- Ballistic laying: target lead, vehicle velocity, flat or lobbed arcs
- Turret mount with acceleration, target pacing and rider clearance
- Gunner mode: third-person sight camera, zoom, lock-on, HUD
- Guard mode: auto-targets hostile mobs, no block damage
- Explosive shells with staged burst effects

## Maths

- **Flight:** stepped the way Minecraft steps a projectile: `p += v`, then `v = 0.99·v − g·ŷ`. Every lay is solved by simulating this, not with closed-form parabolas.
- **Lead:** aim, fly, then take the closest approach between the round's and the target's straight path over each tick, and shift the aim by the miss. Repeat until within 0.3 blocks.
- **Arcs:** sweep elevation in 3° steps to find the furthest-carrying angle, then bisect to the target's distance at its height: above that angle for the lob, below it for the flat arc.
- **Moving vehicle:** its level velocity `u` carries a round sideways by `u·(1 − 0.99ᵗ)/0.01` over `t` ticks. The aim point is pulled back by that much and re-solved 3 times.
- **Mount:** each axis is limited in both acceleration `a` and speed. It runs at the fastest speed that can still stop in the remaining gap `d`, `(√(a² + 8a·d) − a)/2`, on top of the target's own angular rate.
- **Tracking:** alpha-beta filter, `α = 0.5`, `β = α²/(2 − α)` (critically damped).
- **Rider clearance:** the floor at each bearing is the lowest elevation whose barrel segment misses every rider column. The swing takes whichever way costs less traverse time plus extra lift time.

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
| `common/.../demo` | Gun Boat (Tools & Utilities tab) |
| `neoforge`, `fabric` | loader entry points |
| `neoforge/src/test` | ballistics unit tests |

## License

MIT
