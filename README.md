# VehicleGuns

Crewed and self-guarding turrets for any vehicle entity in Minecraft 1.21.1, on NeoForge and Fabric.

Give an entity a gun and VehicleGuns does the rest:

- **Ballistic laying.** Rounds fly as Minecraft moves a thrown thing (drag and gravity). The gun leads moving targets, allows for the vehicle's own velocity, and picks the flat arc for direct fire or the high arc to lob over a wall or hill.
- **A real mount.** The turret and barrel gather speed and settle on their bearing without overshooting. They keep pace with a target crossing the sights, and lift the barrel over the people riding the vehicle as they swing past them.
- **A gunner's seat.** The gunner aims by looking, from a camera behind and over the vehicle. Attack fires, use zooms down the sight and asks for a steady platform, and a key locks on a target and leads it. The sight shows where a round will land, whether the gun is laid, loading and out of reach. There is a hit marker, a compass and an incoming-round marker.
- **Guard mode.** With no gunner aboard the gun guards on its own. It picks hostile mobs by threat, holds fire when a villager or player is on the line, and its rounds hurt only hostile mobs and break no blocks.
- **Shells that burst.** Explosions sized by the round, a staged burst effect with distance-delayed sound, screen shake and flash.

It started as the deck gun of the battleship ferries in [Lapras Ferry Lines](https://github.com/Fantastic-Fanta/LaprasFerry), cut down to what any vehicle needs.

## Try it

Build and run (Java 21):

```bash
./gradlew :neoforge:runClient
```

```bash
./gradlew :fabric:runFabricClient
```

Take a **Gun Boat** from the Tools & Utilities tab and place it on water.

- Alone aboard, you steer and lay the gun yourself. With two aboard, whoever sits behind is the gunner.
- Rounds are fire charges from your inventory, unless you are in creative.
- With nobody aboard it guards against hostile mobs.

## Arm your own vehicle

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

Then draw your turret model at `turret.traverse(partial)` and `turret.elevation(partial)`. [`GunBoatRenderer`](common/src/main/kotlin/dev/vehicleguns/client/GunBoatRenderer.kt) shows how.

Optional hooks on `ArmedVehicle`:

| Hook | Default | Use |
| --- | --- | --- |
| `roundsLeft(gunner)` / `takeRound(gunner)` | unlimited | ammo from an inventory or magazine |
| `targetRules` | hostile mobs for the guard, any living thing for a gunner | spare your own allies, pets or factions |
| `Turrets.wantsSteady(vehicle)` | | slow your vehicle while the gunner zooms or locks on |

`TurretSpec` sets the pivot and barrel, elevation limits, mount speeds for gunner and guard, the round, guard range and watch sweep, and the camera. `GunSettings` holds block griefing, whether gunners hurt players, and the client's shake, flash and particle density.

## Layout

| Folder | What is in it |
| --- | --- |
| `common/.../ballistics` | pure maths: flight, lead solving, arcs, mount axes, target tracking, deck clearance |
| `common/.../api` | what a vehicle implements and configures |
| `common/.../gun` | server gunnery (`Turrets`) and the aiming shared with the client (`Aiming`) |
| `common/.../entity` | the shell |
| `common/.../client` | gunner input and camera, sight, HUD, burst effects |
| `common/.../demo` | the Gun Boat |
| `neoforge`, `fabric` | thin loader entry points |

## Tests

```bash
./gradlew :neoforge:test
```

The ballistics are covered by unit tests: lead solving, arcs from a moving vehicle, spots up a bank, mount motion and tracking.

## License

MIT
