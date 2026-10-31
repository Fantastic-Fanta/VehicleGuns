# VehicleGuns

Turrets for any vehicle entity. Minecraft 1.21.1, NeoForge and Fabric, Kotlin.

Demos: [Blastoise](https://gyazo.com/bfa89033004447d7f4f96d74b3f3dfc9), [Lapras](https://gyazo.com/a979786342eb43d9c5dc383dbc41b148).

## Features

- Ballistic laying: target lead, vehicle velocity, flat or lobbed arcs
- Turret mount with acceleration, target pacing and rider clearance
- Gunner mode: third-person sight camera, zoom, lock-on, HUD
- Guard mode: auto-targets hostile mobs, no block damage
- Explosive shells with staged burst effects
- Machine guns: hitscan mounts with cones, guard bursts, full auto under the gunner's key, heat, tracers
- Torpedoes and missiles: homing rounds with launchers, marking, stamina, wake and underwater bursts
- Magazine: a vehicle ammo store opened with the gunner's inventory key

## Maths

- **Flight:** stepped the way Minecraft steps a projectile: `p += v`, then `v = 0.99·v − g·ŷ`. Every lay is solved by simulating this, not with closed-form parabolas.
- **Lead:** aim, fly, then take the closest approach between the round's and the target's straight path over each tick, and shift the aim by the miss. Repeat until within 0.3 blocks.
- **Arcs:** sweep elevation in 3° steps to find the furthest-carrying angle, then bisect to the target's distance at its height: above that angle for the lob, below it for the flat arc.
- **Moving vehicle:** its level velocity `u` carries a round sideways by `u·(1 − 0.99ᵗ)/0.01` over `t` ticks. The aim point is pulled back by that much and re-solved 3 times.
- **Mount:** each axis is limited in both acceleration `a` and speed. It runs at the fastest speed that can still stop in the remaining gap `d`, `(√(a² + 8a·d) − a)/2`, on top of the target's own angular rate.
- **Tracking:** alpha-beta filter, `α = 0.5`, `β = α²/(2 − α)` (critically damped).
- **Machine guns:** each mount turns along a great circle at a fixed rate, within a cone round its rest direction. It leads by `d / bulletSpeed` ticks of tracked velocity. A bullet lands after the same delay on whatever is first on its line, up to `spread`° off the barrel, spread evenly over the cone.
- **Torpedoes and missiles:** each tick a round turns up to `turn`° along the great circle onto its mark's tracked point, gathering `0.12·speed` a tick up to `speed`. A torpedo's pitch is capped at ±22° and it is held under the water's face; a missile runs at 0.45× speed until it leaves water. A lost mark is replaced by the nearest valid one within 50° of its heading. The burst does `damage` to what it hits and `splash·(1 − r/radius)` around.
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
| `magazine` | none |
| `roundsLeft` / `takeRound` | from the magazine, else unlimited; unlimited in creative |
| `targetRules` | guard: hostile mobs; gunner: any living thing |
| `Turrets.wantsSteady` | true while the gunner zooms or locks on |

`TurretSpec` configures geometry, mount speeds, rounds, guard, camera, machine guns, heat and seekers.

Keys: attack fires, use zooms, C locks on, X machine guns, V torpedoes or missiles. `GunSettings` holds global toggles.

## Layout

| Path | Contents |
| --- | --- |
| `common/.../ballistics` | flight, lead, arcs, mount axes, tracking, vehicle frame |
| `common/.../api` | `ArmedVehicle`, `Turret`, `TurretSpec`, `TargetRules`, `Magazine` |
| `common/.../gun` | server gunnery, machine guns, seekers, shared aiming |
| `common/.../entity` | shell, homing round, damage source |
| `common/.../menu` | magazine menu |
| `common/.../client` | input, camera, sight, HUD, tracers, torpedo and burst effects, renderers, magazine screen |
| `common/.../demo` | Gun Boat (Tools & Utilities tab) |
| `neoforge`, `fabric` | loader entry points |
| `neoforge/src/test` | ballistics unit tests |

## License

MIT
