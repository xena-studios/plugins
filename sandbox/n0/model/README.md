# N0 spike: a neutral game model for NeoSkript (results)

Part of #202 (decisions/2026-11-27-neoskript-joins-the-rewrite.md). The question: what does a game
model between NeoSkript's syntax and each platform look like, what does it cost per call, and how
much of it belongs in the core? Everything below comes from this folder's code, booted on
2026-11-27 (macOS arm64, M-series, other sessions' builds and servers running beside it, so
timings are loose). Nothing here is part of NeoSkript or released.

**The code is on the `spike/n0-model` branch** (head `b5068b9b`), not on `main`: it needs three lines of wiring in the shared builds (below), which would build it in every plugin's CI. Check that branch out to run it.

## What's on the branch

| | |
|---|---|
| `common/` | The model, in shared code (`xena.kmp`: jvm and wasmWasi, so nothing from `java.*`): `Game` and its operations (`Types.kt`, `Game.kt`, `Events.kt`), `SpikeScript` (a hand-written stand-in for compiled script code), `Check` (the boot check) and `Timing` (the timing loop). |
| `bukkit/` | The Paper and Folia adapter (`BukkitGame`, `BukkitEvents`) and a plugin that boots it (`N0ModelPlugin`), compiled against Paper 1.21.11's API. |
| `fabric/` | The Fabric adapter (`FabricGame`, `FabricEvents`) and a mod that boots it (`N0ModelMod`): a branch of core/platform-fabric's Stonecutter build, for 1.21.11 and 26.3. |
| `boot-checks.txt`, `client-checks.txt` | The boot steps: `MODEL PASS` and the timing loop; then a real client (`client.js --plugin n0model`) through the script's triggers. |

Outside it, three lines of wiring: `settings.gradle.kts` includes `:sandbox:n0-model:common` and
`:bukkit`, `core/platform-fabric/settings.gradle.kts` adds the `n0model` branch, and
`scripts/budgets/bots/client.js` has the `n0model` scenario.

```
./gradlew --max-workers=2 :sandbox:n0-model:bukkit:shadowJar :sandbox:n0-model:common:compileKotlinWasmWasi
./gradlew --max-workers=2 -p core/platform-fabric :n0model:1.21.11:collect :n0model:26.3:collect
scripts/boot-test.sh --version 1.21.11 --checks sandbox/n0/model/boot-checks.txt --checks sandbox/n0/model/client-checks.txt sandbox/n0/model/bukkit/build/libs/N0Model.jar
scripts/boot-test.sh --project folia --version 1.21.11 (the same checks and jar)
scripts/boot-fabric.sh --version 1.21.11 --java <JDK 21> (the same checks) core/platform-fabric/build/libs/n0model-fabric-0.0.0+1.21.11.jar
```

## Summary

1. **The shape: the platform's own objects as values, and neutral operations on them.** A player
   is Bukkit's `Player` on Paper and Minecraft's `ServerPlayer` on Fabric, passed as `Any`;
   `game.entities.health(player)` is the model. Kinds of block, item and entity are interned
   types by today's id (`minecraft:stone`), places are the core's `Location` and `WorldRef`, and
   events are neutral kinds whose values the platform binds once, as a script loads. No wrapper
   per value. NeoSkript's compiled code already holds every value as an `Object` and casts it
   where it's used, so this is the same code with the cast moved into the adapter.
2. **The same calls work on Paper, Folia and Fabric.** One hand-written script, one check, three
   platforms: `MODEL PASS` on Paper 1.21.11, Folia 1.21.11, Fabric 1.21.11 and Fabric 26.3, and a
   real client passes all seven player checks (welcome, give, chat, a cancelled chat, set block,
   break, place) on Paper, Folia and Fabric 1.21.11, and its quit is heard on each.
3. **The cost is within the noise on Paper, and 1 to 10 ns a call on Fabric.** On Paper, where
   Bukkit's own calls already cost 4 to 150 ns, the model is within ±3 ns everywhere but
   `location of` (+8 ns: the core's immutable `Location`). On Fabric, where Minecraft's calls are
   field reads of 1 to 3 ns, the model adds 1 to 5 ns per call, and 6 to 11 ns for a block's type
   (the registry's id lookup is a hash map).
4. **About half of it belongs in the core, because the plugins need it too.** Type tables by
   today's id (with 1.8 to 1.12's names), blocks by place, entity basics (location, teleport,
   health, type), world references and ownership by native value: xMarket has the item table
   today, xUtilities and xTeleport each have their own teleport, and xRewind, xPlots and xLimits
   need blocks and entities. The events, their values, Skript's types and every Skript behaviour
   stay NeoSkript's.
5. **Hard, in order:** item, block and entity ids across 1.8.8 to 26.x (tables, not code);
   Fabric's events (no event objects, no unregistering, no place event, damage that can be
   stopped but not changed, a place heard before it happens, quits on a network thread); and
   Folia (it worked as is: Bukkit's events already arrive on the owning region's thread, and
   the hard part is effects that finish later, such as a teleport).

## How NeoSkript does it today

`neoskript-paper` is 94 Java files, 35,000 lines, importing 377 Bukkit and Paper classes. It
registers 88 events, 302 event values and about 680 expressions, effects and conditions through
`neoskript-api`'s `Registrar`:

- **Values are Bukkit objects, typed by Java class.** `registrar.type(id, name, javaClass)` names
  a Skript type after a class (`player` is `org.bukkit.entity.Player`), and an expression is
  `Object get(TriggerContext, Object[] arguments)`, returning a Bukkit object, an array or null.
- **Events are Bukkit classes.** `registrar.event("EvtBlock", List.of(BlockBreakEvent.class, ...),
  filter, patterns)`; `EventDispatcher` registers one Bukkit listener per class the loaded
  scripts use (with `registerEvent`, at Skript's priority, skipping cancelled events) and runs
  the triggers with the event in a `SimpleTriggerContext`.
- **Event values are getters on a class**, resolved by the compiler as the script loads:
  `registrar.eventValue(BlockBreakEvent.class, "player", BlockBreakEvent::getPlayer)`, with Skript's
  time states (-1 past, 1 future) as overloads. `context.event()` is the Bukkit event itself.
- **Folia is already in the API:** `ThreadAffinity` says whether an element touches what it's
  given (`OWNER`: checked, and an effect hops to the owner's thread), only reads what's safe from
  anywhere (`ANY`) or changes global state (`GLOBAL`); the dispatcher knows what each event
  happened to (`owner(event)`), for carrying on after a wait.

So the compiler and runtime are already platform-neutral in shape (`Object` values, getters
resolved once); what's Bukkit is the classes the registrations name and the bodies of the
syntax. The model replaces both, and nothing in the compiled code's calling convention changes.

## The model's shape

```kotlin
interface Game {
    val blockTypes: Types<BlockType>; val itemTypes: Types<ItemType>; val entityTypes: Types<EntityType>
    val worlds: Worlds; val blocks: Blocks; val items: Items; val entities: Entities; val players: Players
    val events: Events; val threads: Threads
}
interface Entities { fun location(entity: Any): Location; fun health(entity: Any): Double; fun teleport(entity: Any, to: Location, done: ((Boolean) -> Unit)?); ... }
interface EventBinding { fun value(key: EventValueKey): EventValue?; val cancellable: Boolean; fun setCancelled(event: Any, cancelled: Boolean); fun owner(event: Any): Any?; val async: Boolean }
fun interface EventValue { fun get(event: Any): Any? }
```

- **Values.** The platform's own objects, as `Any`. Where a platform has none, the adapter makes
  a small one: a block on Fabric is `FabricBlock(level, pos)`, equal by place as Bukkit's
  `CraftBlock` is. Values compare as the platform's do, so `is`, lists and variables behave as
  today, and on Bukkit they stay what Skript's add-ons (SkBee) expect.
- **Types.** `BlockType`, `ItemType`, `EntityType`: one object per id, made once at startup from
  the platform's registry, so `type of block is stone` is a pointer compare. The platform turns
  its own object into a type with an array (Bukkit: by `Material.ordinal`; Fabric: by registry raw
  id, which is each type's index), never a map at run time. A type the server doesn't have isn't
  in its table, so a script naming it fails to load. Paper 1.21.11 and Fabric 1.21.11 both list
  1,166 blocks, 1,505 items and 157 entity types; Fabric 26.3 1,286, 1,658 and 161.
- **Places.** The core's `Location` (immutable, with `WorldRef`) and `WorldRef`, so a place means
  the same in the core's scheduler (`Where.At`), the plugins' APIs and NeoSkript.
- **Events.** An `EventKind` (`join`, `block break`, `damage`) lists the values triggers may ask
  for. Each platform gives an `EventBinding` per kind: its getters, whether it can be cancelled,
  what the event happened to (its owner, for threads), and whether it runs async. The script
  resolves every getter as it loads (`binding.value(PLAYER)`) and calls it directly after; a
  value the platform can't give is a load error on that platform, never a null at run time. The
  event object triggers get is the platform's: Bukkit's own event on Paper, so Skript's add-ons
  and other plugins see the same one; on Fabric the adapter's (`FabricChatEvent`), or the core's
  `BlockChange` for breaks and places.
- **Cancellation** is the binding's: Bukkit's `Cancellable`, or a flag on the Fabric adapter's
  event that its callback returns (`ALLOW_CHAT_MESSAGE`, `ALLOW_DAMAGE`), or the core's
  `BlockChange.isCancelled`.
- **Threads.** `threads.regions` says whether there's more than one owner (Folia); only then does
  compiled code need `threads.owns(value)`, checked once per trigger. `threads.runOn(value)` runs
  an effect on the owner's thread (Folia's entity or region scheduler; the main thread
  elsewhere). That's NeoSkript's `ThreadAffinity` as it is, now with a platform under it.
- **Text** is a `String` in the spike; it's the core's `Component` in N2 (the core's renderers
  exist on both platforms).

### The 20 syntaxes

| Skript | Model | Paper | Fabric |
|---|---|---|---|
| `on join` / `on quit` | `EventKind.JOIN`, `QUIT` | `PlayerJoinEvent`, `PlayerQuitEvent` | `ServerPlayConnectionEvents.JOIN`, `DISCONNECT` (moved to the server thread) |
| `on chat` | `CHAT`, values `player`, `message` | `AsyncChatEvent` (async), message as legacy text | `ServerMessageEvents.ALLOW_CHAT_MESSAGE`, on the server thread |
| `on break` / `on place` | `BLOCK_BREAK`, `BLOCK_PLACE` | `BlockBreakEvent`, `BlockPlaceEvent` | the core's world hooks (`ChangeCause.PLAYER`, its mixins) |
| `on damage` | `DAMAGE`, values `victim`, `attacker`, `damage` | `EntityDamageEvent`, damage changeable | `ServerLivingEntityEvents.ALLOW_DAMAGE`, damage read-only |
| `on death` | `DEATH`, values `victim`, `attacker` | `EntityDeathEvent` | `ServerLivingEntityEvents.AFTER_DEATH` |
| `send "..." to player` | `players.send` | `sendMessage` | `sendSystemMessage(Component.literal(..))` |
| `teleport` | `entities.teleport(.., done)` | `teleport`, `teleportAsync` on Folia | `teleportTo(level, ..)` |
| `give player 16 dirt` | `items.create`, `players.give` | `ItemStack`, `addItem`, the rest dropped | `ItemStack`, `inventory.add`, the rest dropped |
| `set block at .. to glass` | `blocks.at`, `blocks.setType` | `getBlockAt`, `setType` | `FabricBlock`, `setBlock(.., 3)` |
| `kill` | `entities.kill` | `setHealth(0)` | `kill(level)` |
| `cancel event` | `binding.setCancelled` | `Cancellable` | the adapter's flag, returned to Fabric API |
| `player`, `victim` (event values) | `EventValue.get` | getters on Bukkit's event | getters on the adapter's event |
| `location of` | `entities.location`, `blocks.location` | `getLocation`, made the core's | `x`, `y`, `z`, `yRot`, `xRot` |
| `block at` | `blocks.at` | `World.getBlockAt` | `FabricBlock(level, BlockPos)` |
| `type of block` | `blocks.type` | `Material.ordinal` to an array | `BLOCK.getId(state.block)` to an array |
| `tool of player` | `players.heldItem` | `itemInMainHand` | `mainHandItem` |
| `name of` | `entities.name`, `players.name` | `getName` | `name.string`, `scoreboardName` |
| `health of` | `entities.health` | `LivingEntity.getHealth` | `LivingEntity.health` |
| `world of` | `entities.world`, `worlds.name` | `getWorld` | `level()` |
| `item amount` | `items.amount`, `setAmount` | `ItemStack.amount` | `ItemStack.count` |

## What was run

| Server | Boot check | Client (Joiner, mineflayer) |
|---|---|---|
| Paper 1.21.11-132, Java 21 | `MODEL PASS`, timing loop | 7 of 7 checks; the server logs each trigger |
| Folia 1.21.11-14, Java 21 | `MODEL PASS` (no timing loop: see below) | 7 of 7 |
| Fabric 1.21.11, Java 21 | `MODEL PASS`, timing loop | 7 of 7 |
| Fabric 26.3, Java 25 | `MODEL PASS`, timing loop | not run: mineflayer doesn't join 26.3 yet |

The boot check, as the server starts, with no player: set a block, read its type and read the
block back at its own location; 16 dirt, its amount, and a new amount; spawn a zombie, its type,
name, health, world and location; two hits through the platform's own damage event, 7 (which
the script's `on damage` cancels: health stays 20) and 3 (health 17); a teleport; a kill, heard
by `on death`; the block back to air. The client then joins (welcomed, 16 dirt, glass set two
blocks north), chats (answered), says "cancel me" (never shown), breaks the glass holding dirt
(answered with `minecraft:glass` and `minecraft:dirt`) and places dirt (answered with
`minecraft:dirt, 16 left`), and quits (logged).

Not built or run here: Spigot and CraftBukkit, and anything before 1.21.11. The 1.8.8 path below
is a design, not tested code.

## The cost per call

`Timing.pair` in `common/`: on the server thread of a real server as it starts, each call
2,000,000 times per round, the model's and the platform's own taking turns, best of 7 rounds after
a warm-up. Both sides start from an `Any` read from an array, and two of each value (two zombies,
two blocks, two events) take turns, so the JIT can neither smart-cast nor hoist a read out of the
loop. "Direct" is what NeoSkript's Paper code does today (cast, call Bukkit); on Fabric, Minecraft's
own calls. Nanoseconds per call; two boots each, to show the noise:

| | Paper direct | Paper model | Fabric 1.21.11 direct | Fabric 1.21.11 model | Fabric 26.3 direct | Fabric 26.3 model |
|---|---|---|---|---|---|---|
| `health of` | 12.4, 12.0 | 14.3, 12.5 | 2.7, 2.9 | 1.7, 1.7 | 1.6 | 1.6 |
| `type of block is stone` | 18.4, 18.2 | 18.6, 18.9 | 20.6, 22.9 | 28.6, 30.6 | 20.4 | 25.9 |
| `block at` | 6.9, 7.0 | 6.5, 6.3 | | | | |
| `block at`, then its type | 21.0, 23.4 | 21.7, 21.3 | 25.1, 31.8 | 32.7, 41.3 | 25.4 | 36.4 |
| `location of` | 10.4, 10.3 | 16.9, 18.1 | 2.1, 2.2 | 6.3, 6.9 | 1.3 | 6.3 |
| `name of` | 147, 150 | 146, 145 | 45.9, 53.3 | 46.0, 51.3 | 48.1 | 43.9 |
| `event-entity` (victim) | 4.2, 7.4 | 4.4, 4.5 | 1.2, 1.2 | 4.1, 4.8 | 2.4 | 3.9 |
| `the damage` | 4.4, 9.1 | 5.4, 5.7 | 2.0, 2.1 | 3.4, 3.6 | 2.1 | 3.5 |
| `cancel event` | 3.7, 3.6 | 2.1, 2.1 | 0.9, 0.9 | 2.1, 2.2 | 0.4 | 2.1 |
| thread check | 2.4, 2.9 | 3.2, 3.2 | 2.1, 2.2 | 2.4, 3.0 | 2.1 | 2.9 |
| `health of` through a wrapper object | 11.1, 10.7 | 13.4, 12.6 | | | | |

Reading it:

- **On Paper the model costs nothing measurable** but in `location of`: Bukkit's own calls do the
  work (a `CraftBlock` per `getBlockAt`, the chunk's state for a type, a translated component for
  a name), and the adapter's cast and array read vanish beside them: each interface has one
  implementation on a server, which the JIT can inline into the call site. `location of` is +7 to 8 ns: Bukkit's
  `Location`, then the core's `Location`, and the world's `WorldRef` found by name in a map.
- **On Fabric the model adds what Minecraft doesn't spend:** 1 to 3 ns for an event value or a
  cancel (a call through `EventValue` that the JIT doesn't flatten as it does a field read, and a
  boxed `Double` for `the damage`), 4 to 5 ns for `location of` (the core's `Location` against
  reading three fields), and 5 to 11 ns for a block's type: `BuiltInRegistries.BLOCK.getId` is a
  hash map lookup. A field of our own on `Block` (a mixin) would make that an array read.
- **What it means for a script:** a trigger making ten calls through the model pays under 100 ns
  more on Fabric, and nothing measurable on Paper, against a Bukkit event dispatch and NeoSkript's
  own trigger run that cost far more. The budget question for N2 is allocation (`location of`)
  more than calls.
- **A wrapper object per value** cost about 2 ns here, because the JIT's escape analysis removed
  the wrapper in a loop that doesn't keep it. In NeoSkript a value escapes at once (into an
  `Object[]` of arguments, a variable, a list), so a wrapper is a real allocation per value, two
  wrappers for one entity compare unequal unless every class defines equality, and on Bukkit
  every Skript add-on would need unwrapping at its edge. The reasons against wrappers are those,
  not these nanoseconds.
- **Caveats.** A loop on a server as it starts, not JMH; other sessions' builds were running; the
  Fabric jar is shrunk by R8 (as a release is) and the Paper one isn't; one machine. Differences
  under about 3 ns are noise (Paper's `cancel event` "model faster" is). Folia's watchdog reports
  a region held 5 s, and the loop holds it about 10, so Folia skips it: its calls are Paper's.

## Core or NeoSkript

The repo's rule is that shared code moves to the core on its second user. What the core has
already, from the plugins so far: `Location`, `WorldRef`, `PlayerRef` (core-api); the scheduler
with Folia's model (`Where.At`, `Where.On`, `Threading`); the world hooks with `BlockChange`,
`BlockData` and `ItemData` (block, container and entity changes, for xRewind and xLimits, with
mixins on Fabric); the chat bridge; text and its renderers; per-platform helpers (`BukkitRefs`,
Fabric's `Entity.location`, entities by UUID). What the plugins still do on their own: xUtilities
and xTeleport each have a `teleport(player, Location, done)` and their own block reads for safe
spots; xMarket has the only item table, with 1.8 to 1.12's names (`prices/legacy.txt`,
`BukkitItems`).

| Part | Where | Its users |
|---|---|---|
| Type tables by today's id, with arrays from each platform's objects, and 1.8 to 1.12's names and data values | **core** | xMarket (items, today), NeoSkript, xRewind (blocks, M12), xLimits (entities, M14) |
| Blocks by place: at, type, set type, the state as text (`BlockData.data`) | **core** | xRewind's rollback, xPlots' reset, xUtilities' and xTeleport's safe spots, NeoSkript |
| Entity basics on the platform's object: UUID, type, location, world, teleport (Folia's async one with a callback), health | **core** | xUtilities (`/heal`, teleports), xTeleport, xLimits, NeoSkript |
| Worlds: one `WorldRef` per world, kept | **core** (a fix: `BukkitRefs.ref` and Fabric's `Level.ref` make a new one per call) | everything that makes a `Location` |
| Ownership by the platform's object (`owns(entity)`, `runOn(block)`), beside `Where` | **core** | NeoSkript, and plugins that hold entities rather than UUIDs |
| Damage, as a cancellable event, beside the world hooks | **core**, once a plugin needs it (xLimits?); NeoSkript's own until then | NeoSkript |
| Event kinds and their bindings: Skript's 88 events and 302 values, time states, changers (`set damage`) | **NeoSkript** | NeoSkript |
| Skript's types, item types with aliases, conversions, comparisons, its exact behaviour (`location of block` is its middle) | **NeoSkript** | NeoSkript |
| The bridge to Skript's add-ons (Bukkit only) | **NeoSkript** | NeoSkript |

So the core grows "operations on the platform's own objects" (this folder's `Blocks`,
`Entities`, `Items`, `Worlds`, `Types`, from `common/`), implemented in platform-bukkit and
platform-fabric. The plugins' APIs keep naming players by `PlayerRef`, and their adapters call
these underneath; NeoSkript calls them directly, because a script holds the object already and
can't pay a UUID lookup per expression. Skript's events and its semantics never go into the core:
the core's events stay the plugins' (world hooks, joins, chat), and NeoSkript's bindings use them
where they fit (Fabric's breaks and places) and the platform's own events where Skript needs the
real thing (every Bukkit event, for add-ons and for `event-item`, drops and the rest).

## What's hard

**Item, block and entity ids across versions.** From 1.13 Bukkit's `Material` is today's id
(the spike's arrays by ordinal; legacy `LEGACY_` materials left out). Before 1.13 an item is a
material and a data value, mapped by xMarket's table from Mojang's data fixer (485 lines for
items); blocks need the same for block states (id and metadata to a state string), and entity
names changed at 1.11, 1.13, 1.16 and 1.20.5. The decision already says these tables are
generated from Minecraft's data per version; the spike adds that they belong in the core,
indexed by each platform's ordinal or raw id so a lookup is an array read. Two smaller points:
the spike names Bukkit's entity types by `name.lowercase()`, which matches on 1.21.11 but not in
general (use the keys); and Fabric's raw ids change between starts, so a type's index is only
ever for this run (variables store ids).

**Fabric's events.** Fabric API gives callbacks, not event objects, so the adapter makes small
ones. Its callbacks can't be unregistered, so the adapter registers each once and keeps scripts'
triggers on lists of its own, in priority order (Fabric has phases, not priorities). It has no
place event: the core's world hooks are what hear one, and they hear it before the block is in
the world, where Bukkit's event comes after; the adapter's block carries the state the change
leaves, so `type of event-block` is `minecraft:dirt` on both (the first client run read
`minecraft:air`). `ALLOW_DAMAGE` can stop a hit but not change it, so `set damage` needs a mixin
there. A kill on Fabric is a hit first (the script heard a damage before the death), where
Bukkit's `setHealth(0)` isn't. `DISCONNECT` came on a Netty thread on 1.21.11 when the client
quit, so the adapter moves quits to the server thread; the core's `FabricPlatform` fires its
`PlayerQuitEvent` from the same callback, on whatever thread it comes. Minecraft's names drift per
line: this small adapter broke twice between 1.21.11 and 26.3 (`drop` takes a `Prediction`,
`EntityType.ZOMBIE` moved), handled with the core's Stonecutter constants. These differences are
what the differential tests against Paper (N5) have to pin down.

**Folia.** Easier than expected, because Bukkit's events already arrive on the thread that owns
what they're about: the join trigger set a block and gave items, and the break, place, damage and
death triggers read their blocks and entities, with no hop of ours (Folia throws when a thread
touches what it doesn't own, and nothing did); the check ran on the region of the world's middle. What's left is effects that
finish later (`teleportAsync`, so a script's `teleport` becomes a waiting effect there, as
NeoSkript's `ThreadAffinity` foresees), work on another region's entity (`runOn`), chat (async on
Paper and Folia, on the server thread on Fabric: `EventBinding.async`), and long work holding a
region past the watchdog's 5 s.

**What scripts see is the platform's value.** Because values are the platform's own, the type
checker in shared code needs the platform to say which Skript type a value has (today
`registrar.kindClass` by Java class; in the model, `isPlayer`, `isEntity` and the rest, or one
`typeOf(value)` per platform), and Skript's text (legacy colour codes) has to meet the core's
`Component`.

## Recommendation for N2

1. **Take this shape:** the platform's own objects as values, neutral operations on them, types
   by today's id in arrays, the core's `Location` and `WorldRef`, events as neutral kinds with
   bindings resolved as scripts load. No wrappers.
2. **Put the operations NeoSkript shares with the plugins in the core** (the table above), as
   the plugins that need them arrive: blocks with xRewind (M12), regions with xPlots (M13),
   entities with xLimits (M14), the type tables with whichever comes first (xMarket's table is the
   seed). NeoSkript's N2 then builds on what they proved, as the decision orders. Keep NeoSkript's
   event kinds, bindings and Skript semantics in NeoSkript.
3. **On Paper and Folia (N2), keep Bukkit's own events as the event objects**, with bindings
   over them: add-ons see what they expect, and the conversion of the standard library is mostly
   mechanical (each Bukkit call in a syntax's body becomes the model's, area by area). Convert one
   area first (entities) and run Skript's suite and the differential tests on it before the rest.
4. **Fix three things in the core first:** keep one `WorldRef` per world (`BukkitRefs.ref` and
   Fabric's `Level.ref` allocate per call); move Fabric's quit events to the server thread; give
   Fabric's type lookups an array (a field on `Block`, `Item` and `EntityType` by mixin, or a
   one-entry cache) before N5.
5. **Measure in the core's benchmarks** once the operations live there (core/core-benchmarks, JMH,
   on the fake platform), and keep this folder's in-server loop as a boot check that the
   platform's calls stay within noise, against the plugins' budgets.
