# M0: the budgets, measured

This covers the last open parts of #121: numbers for the budgets in ROADMAP.md ("Fast, robust, light"), measured on today's plugins; R8 on the spike jars; and CraftBukkit. It ran on 2026-11-23 on an M1 Pro (10 cores, 32 GB, macOS arm64) with Temurin 8 (Zulu arm64), 17, 21 and 25. Nothing here is wired into the root build.

```
sandbox/m0/budgets/
  measure.sh       one configuration: a Paper server with some plugins, booted 3 times for startup, then heap, an idle profile and a bot-load profile
  run-all.sh       every configuration behind the tables below
  analyze.py       reads measure.sh's folders and prints the tables
  log4j2.xml       Paper's console with milliseconds since JVM start, so plugin load and enable times can be read off the log
  bots/load.js     10 mineflayer bots: walk, break and place blocks, chat, run the plugins' commands, one rejoins every 30 s
  build.gradle.kts R8 over the spike jars (its own build: settings.gradle.kts, gradlew)
  r8/*.pro         the keep rules
```

To rerun:

```
# the 1.x plugins, from the maintenance branch (JDK 21)
git worktree add ../maint origin/maintenance && (cd ../maint && ./gradlew build -x test)
(cd bots && npm install)
SERVERS=<outside the repo> PAPER=<paper-1.21.11-132.jar> TPL=<a Paper run's cache/ libraries/ versions/> \
  ASPROF=<async-profiler 4.6>/bin/asprof ./run-all.sh <folder with the 11 plugin jars>
python3 analyze.py $SERVERS <jars>
# R8
./gradlew shrink -Pspike.jars=<folder with Hello.jar, HelloCloud.jar, m0-sponge-0.0.1.jar>
```

## How it was measured

- **Server:** Paper 1.21.11-132 on Java 21, `-Xms1G -Xmx1G`, flat world, view and simulation distance 6, offline mode. The plugins are the released 1.x jars, built from `maintenance` (950ff3f).
- **Configurations:** no plugins (`base`, twice), all 11 (`all`, twice), all but xLimbo (`all-nolimbo`, see the load section), and each plugin alone.
- **Startup:** each configuration boots once to create its world and configs, then twice more for timing, then a fourth time for the rest. A plugin's *load* and *enable* times run from its `Loading server plugin` or `Enabling` line to the next line logged by anything else; the tables give the median of the last three boots, with the first boot in brackets.
- **Heap:** 30 s after `Done`, two full GCs (`jcmd GC.run`), then the used heap, `VM.classloader_stats` (classes and metaspace in plugin class loaders) and the class histogram. It is very repeatable: `base` 212.0 MB and 212.2 MB, `all` 262.0 MB and 262.4 MB.
- **Tick cost:** async-profiler 4.6, CPU samples every 1 ms of CPU time, per thread. A sample counts for a plugin when any frame of its stack is in `co.xenastudios.<plugin>` (each plugin relocates the core into its own package, so this covers the core too). Server-thread ms a tick = samples / (seconds × 20). Off-thread = CPU ms a second on all other threads. Idle: 90 s. Load: 120 s with 10 bots, after 20 s to settle. Paper's `/mspt` 1-minute average is next to it.
- JFR was tried first and dropped: on macOS its execution sampler saw the server thread about 1 sample in 17 at a 1 ms period, too few to split 0.01 ms between plugins.
- **A busy machine:** from about 21:35 to 22:05 something else on the machine pushed the load average past 200 (an Android emulator and other sessions). Runs from then (`xrewind`, `xstats`, `xvote`, `zchat`, `base-2` and the first `all-nolimbo`, which showed a 1.5 s tick) were thrown away and rerun with a load average of 3 to 18; `measure.sh` now records `uptime` before and after each run. `all-2` (21:49) is kept because it agrees with `all`, but it is the noisier of the two.
- **The noise floor:** `Done` varied by 1 s between the two `base` runs (6.1 s and 7.2 s), so the server's total startup can't measure a plugin; the per-plugin log times can. `/mspt` under load varied 3.6 to 4.4 ms between identical runs, so it can't see a 0.05 ms plugin either; the profile can.

## Today's plugins

### Jar size and classes

| Plugin | Jar (KB) | Classes | Classes loaded | Metaspace (MB) |
|---|--:|--:|--:|--:|
| xBackup 1.0.1 | 483 | 228 | 156 | 0.7 |
| xBans 1.0.1 | 349 | 175 | 215 | 1.0 |
| xLimbo 2.1.0 | 185 | 110 | 153 | 0.6 |
| xMoney 1.0.1 | 369 | 198 | 192 | 0.8 |
| xPlots 1.0.2 | 290 | 146 | 193 | 0.8 |
| xRewind 1.1.1 | 327 | 184 | 177 | 0.7 |
| xStats 1.0.1 | 399 | 203 | 224 | 0.9 |
| xTeleport 1.0.1 | 293 | 157 | 200 | 0.8 |
| xUtilities 2.1.0 | 163 | 89 | 143 | 0.5 |
| xVote 1.0.1 | 320 | 168 | 195 | 0.8 |
| zChat 3.2.0 | 189 | 106 | 130 | 0.5 |
| **All 11** | **3,367** | | **1,980** | **8.2** |

"Classes" counts the class files in the jar; "Classes loaded" and "Metaspace" come from the plugin's class loader after startup, with the plugin alone. For xBans, xStats and xTeleport the loader defined more classes than the jar has files; that wasn't looked into.

### Startup (ms, onLoad + onEnable on the server thread)

| Plugin | Alone | Alone, less Paper's 60 ms | With the other 10 (`all`) | `all-2` | `all-nolimbo` |
|---|--:|--:|--:|--:|--:|
| xBackup | 140 | 80 | 42 | 44 | 45 |
| xBans | 184 | 124 | 66 | 69 | 76 |
| xLimbo | 82 | 82 | 73 | 88 | |
| xMoney | 329 | 269 | 232 | 240 | 273 |
| xPlots | 346 | 286 | 59 | 61 | 60 |
| xRewind | 372 | 312 | 227 | 257 | 255 |
| xStats | 177 | 117 | 117 | 124 | 132 |
| xTeleport | 197 | 137 | 53 | 55 | 56 |
| xUtilities | 151 | 91 | 45 | 46 | 52 |
| xVote | 342 | 282 | 55 | 59 | 63 |
| zChat | 146 | 86 | 44 | 48 | 52 |
| **Sum** | | | **1,013** | **1,091** | |

- Load (onLoad) is 0–6 ms for every plugin; the time is all in onEnable.
- Alone, the plugin is the last thing enabled, so its time runs to Paper's next line, which includes about 60 ms of Paper's own work (the gap between `Done preparing level` and spark's first line with no plugins). The second column takes that off.
- A plugin alone also pays for warming up what the family shares: the SQLite driver's native library, the JIT, Adventure and MiniMessage. With the others installed most plugins enable in 45–75 ms.
- **What's slow:** xMoney and xRewind (230–270 ms even in the family) open their SQLite database and run the schema on the server thread. xStats (120 ms) also starts its web server. The first plugin to open SQLite pays about 200 ms (xPlots and xVote: 290 ms alone, 60 ms in the family).
- xLimbo's world isn't in these numbers: it loads 121 chunks after `Done` ("loaded in 3694 ms"), off the startup path.
- First boots (configs and databases created, jar remapped) were within ±30 ms of these, except xStats and xVote, which enable in 9–12 ms on a first boot because they have nothing to load yet.

### Heap after load (MB, after two full GCs)

| Run | Heap used | Over base | Metaspace | Our instances (KB, shallow) |
|---|--:|--:|--:|--:|
| base (no plugins) | 212.0 / 212.2 | | 120.4 | |
| xBackup | 213.9 | 1.9 | 126.2 | 4 |
| xBans | 214.5 | 2.5 | 127.7 | 15 |
| xLimbo | 255.8 | 43.8 | 130.9 | 4 |
| xMoney | 214.1 | 2.1 | 127.3 | 8 |
| xPlots | 214.2 | 2.2 | 127.3 | 9 |
| xRewind | 221.5 | 9.5 | 130.5 | 6 |
| xStats | 214.2 | 2.2 | 127.5 | 15 |
| xTeleport | 214.2 | 2.2 | 127.4 | 12 |
| xUtilities | 214.0 | 2.0 | 126.1 | 10 |
| xVote | 213.9 | 1.9 | 127.5 | 7 |
| zChat | 213.6 | 1.6 | 126.1 | 6 |
| all 11 | 262.0 / 262.4 | 50.2 | 144.1 | 97 |
| all but xLimbo | 226.2 | 14.1 | 140.2 | 93 |

- A plugin costs about 2 MB of heap and 0.5–1 MB of metaspace.
- xLimbo's 44 MB is its world (121 chunks kept loaded around its spawn).
- xRewind's 9.5 MB is mostly byte arrays (3.8 MB), map nodes and strings and one `CraftWall` per wall block state: a table of every block state built at enable.
- Metaspace grows 6–7 MB with any one plugin (120.4 MB to 126–128 MB) though its own loader holds under 1 MB: the rest is server and JDK classes the first plugin makes Paper load (plugin loading, SQLite, MiniMessage). It is paid once; all 11 together add 24 MB.

### Idle tick cost (90 s, no players)

| Plugin | Server thread, alone (ms/tick) | Server thread, in `all` / `all-2` / `all-nolimbo` | Off-thread, alone (CPU ms/s) | Off-thread, in the family |
|---|--:|--:|--:|--:|
| xBackup | 0.012 | 0.008 / 0.009 / 0.012 | 1.33 | 2.3 / 2.4 / 3.5 |
| xLimbo | 0.002 | 0.002 / 0.001 / – | 0 | 0 |
| xMoney | 0 | 0 | 0.71 | 1.0 / 0.8 / 0.5 |
| xRewind | 0 | 0 | 0.22 | 0.4 / 0.2 / 0.1 |
| the other 7 | 0 | 0 | 0 | 0 |

`/mspt` idle: 0.2–0.7 ms for every configuration, with or without plugins. Zero means no sample in 90 s (under 0.0006 ms a tick).
- xBackup does a little on the server thread every tick (about 12 µs) and wakes a thread about 2 ms a second. xMoney's writer thread and xRewind's queue poll while there is nothing to write. All of this is small, but it isn't "nothing", and the rewrite's rule is nothing.

### Tick cost under load (10 bots, 120 s)

The bots joined, walked, broke and placed blocks in creative mode, chatted every 10 s each and ran a command every 15 s each (`/balance`, `/baltop`, `/stats`, `/homes`, `/sethome`, `/home`, `/spawn`, `/vote`, `/ping`, `/plots`, `/warps`, `/msg`, `/tpa`, `/tpdeny`); one rejoined every 30 s. In a run: about 150 chats, 100 commands, 600 blocks broken and 650 placed.

xLimbo sends every joining player to its own world, where they can't build, so in `all` and `all-2` the bots never built in the main world (about 230 and 120 blocks placed against 650) and the other plugins saw fewer block events. `all-nolimbo` leaves xLimbo out so they do.

| Plugin | `all` (ms/tick) | `all-2` | `all-nolimbo` | Worst | Off-thread, worst (CPU ms/s) |
|---|--:|--:|--:|--:|--:|
| xBackup | 0 | 0 | 0.002 | 0.002 | 0.30 |
| xBans | 0.002 | 0 | 0 | 0.002 | 0.01 |
| xLimbo | 0.007 | 0.010 | – | 0.010 | 0.42 |
| xMoney | 0.003 | 0.003 | 0.002 | 0.003 | 0.72 |
| xPlots | 0.003 | 0.001 | 0 | 0.003 | 0 |
| xRewind | 0.004 | 0.002 | 0.008 | 0.008 | 1.18 |
| xStats | 0.003 | 0.002 | 0.003 | 0.003 | 0.12 |
| xTeleport | 0.007 | 0.005 | 0.005 | 0.007 | 0.17 |
| xUtilities | 0.001 | 0 | 0 | 0.001 | 0.03 |
| xVote | 0.001 | 0.002 | 0.002 | 0.002 | 0.01 |
| zChat | 0.001 | 0 | 0.001 | 0.001 | 0.28 |
| **All, summed** | 0.032 | 0.025 | 0.023 | | |
| `/mspt` 1-minute average | 4.4 | 3.6 | 3.8 | | base: 3.9 |

- **Every plugin is under 0.01 ms a tick with 10 players**, 50 times under the roadmap's 0.5 ms. Chat is async on Paper, so zChat's cost shows off-thread (0.3 ms a second).
- What a plugin causes without running its own code isn't in these numbers. In `all`, the server thread had 0.5 ms a tick more than `base`, almost all in natural mob spawning and `CustomChunkGenerator.getMobsAt`: the bots stood in xLimbo's world, which spawns mobs, while the flat main world spawns almost none. A plugin that creates or keeps a world (xLimbo, xPlots) gets the cost of that world.

## R8 on the spike jars

R8 9.5.33 (`com.android.tools:r8`, `--classfile`), with the JDK, the server API and anything fetched at runtime (stdlib, Cloud, Adventure) as library jars. Hello and HelloCloud are the Java 8 bytecode builds (`-Pm0.jvm=1.8`) of `sandbox/m0/bukkit`; the Sponge jar is `sandbox/m0/mods/sponge` as built (stdlib, cloud-core and geantyref shaded, relocated and already `minimize()`d by Shadow).

| Jar | Before | After R8 | Classes | Boots and runs its command |
|---|--:|--:|--:|---|
| Hello (stdlib fetched) | 23.0 KB | 19.8 KB (−14%) | 9 → 9 | Spigot 1.8.8 on Java 8, Paper 1.21.11 on Java 21: `/hello` |
| HelloCloud (stdlib, Cloud, Adventure fetched) | 30.5 KB | 24.7 KB (−19%) | 12 → 11 | same servers: `/hellocloud R8` (Cloud parses, MiniMessage reply) |
| Sponge plugin (stdlib + Cloud shaded) | 1,966 KB | 269 KB (−86%) | 990 → 271 | SpongeVanilla 1.21.10-17.0.0 on Java 21: `xspike hello Console` → `Hello, Console! (Kotlin + Cloud + Adventure on Sponge 1.21.10)` |

R8 matters where something is shaded: the Kotlin plugins on Bukkit are already small because the stdlib is fetched. Each jar's mapping file is kept with the build (`build/r8/<name>.map`); stack traces keep line numbers (`-keepattributes SourceFile,LineNumberTable`).

What the keep rules had to learn (each one broke the Sponge plugin at startup until added; they are in `r8/`):
- **Main classes and delegates.** Keep the Java shim (`extends JavaPlugin`, no-arg constructor) and every class implementing the loader's `Delegate` (it is loaded by name), `@EventHandler` methods, Sponge's `@Plugin` class and its `@Inject` constructor and `@Listener` methods. A plugin's public API (`co.xenastudios.**.api.**`) keeps its names; the spike jars have no API package, so that rule matched nothing here.
- **Renamed classes go in a package of the plugin's own** (`-repackageclasses co.xenastudios.m0.hello.r8`). Bukkit's `PluginClassLoader` looks classes up in a cache shared by every plugin, so two jars must never both have `a.a`. The first version put Hello's and HelloCloud's into the same package.
- **Enums stay enums** (`-keepclassmembers enum * { values(); valueOf(String); }`). R8's enum unboxing broke Cloud: "class ...r3 not an enum".
- **Type tokens keep their generic superclass.** R8 in full mode keeps a `Signature` only on classes a rule keeps, so geantyref's `TypeToken` subclasses lost theirs: "Invalid TypeToken; must specify type parameters". Kept: subclasses of geantyref's, Gson's and Guice's token types.
- **Cloud's interfaces stay.** Cloud keys its services by `TypeToken<CommandPreprocessor<C>>`; R8 removed interfaces nothing called through and wrote `Object` into those signatures, so two services clashed ("Service of type 'Object' has already been registered"). `-keep,allowobfuscation interface <relocated cloud>.**` costs about 40 KB. R8's compatibility mode (`--pg-compat`) did not fix this, so full mode stays.
- **Sponge reads a listener's generic event type from the local variable table**, not the method signature (`ListenerClassVisitor.visitLocalVariable`): without it, "Incorrect number of type arguments for RegisterCommandEvent: expected 1, but got 0". `-keepattributes LocalVariableTable` is not enough in R8; `-keepparameternames` is.
- R8 copies the jar's resources itself; the build then drops Maven's pom files and Kotlin's `.kotlin_builtins` (read only by kotlin-reflect).

**For the rewrite:** the shared rules (`common.pro`) belong in build-logic, with a platform file each. Each shaded library brings its own reflection, so a jar is shrunk only if CI boots it and runs a command, as here.

## CraftBukkit

BuildTools (#202) with `--compile craftbukkit`, reusing the bukkit spike's work folders: 1.8.8 on Zulu 8 (32 s), and `--rev latest`, which is **26.3** (Jenkins build 4665), on Java 25 (1 min 51 s). Jars: `craftbukkit-1.8.8.jar` 17.3 MB, `craftbukkit-26.3.jar` 81.4 MB. Booted with the bukkit spike's `boot.sh` and the Java 8 bytecode Hello.jar, running `/hello` from the console:

| Server | Java | Stdlib from | First boot: onLoad / onEnable | Second boot (cached) | `/hello` |
|---|---|---|---|---|---|
| CraftBukkit 1.8.8 (git-Bukkit-741a1bd) | 8 (Zulu arm64) | our bootstrap: downloaded, then `URLClassLoader.addURL` by reflection | 939 ms (download) / 16 ms | 31 / 35 ms | `squares=[1, 4, 9, 16, 25]` |
| CraftBukkit 1.8.8 | 17 | our bootstrap, through the JDK's trusted lookup | 765 ms (download) / 14 ms | – | same |
| CraftBukkit 26.3 (4665-Bukkit-227878d) | 25 | the server, from `libraries:` | 11 / 16 ms | 12 / 20 ms | same, and `getPlugin("HelloProvided")` resolves through `provides:` |

**CraftBukkit behaves like Spigot** on both ends, so it needs nothing of its own. Two harmless messages: 1.8.8 logs `FileNotFoundException` for `ops.json` and the other lists on a fresh folder, and its update check gets HTTP 403 from SpigotMC's Stash; 26.3 logs `No key layers in MapLike[{}]` for `boot.sh`'s `level-type=FLAT` without generator settings, with or without the plugin.

## Proposed budgets

| | Today (1.x, measured) | Proposed budget | Gate |
|---|---|---|---|
| **Startup** | 0–6 ms load; enable 42–76 ms for 7 plugins in the family, 117–132 ms xStats, 227–273 ms xMoney and xRewind (SQLite on the server thread); 80–312 ms alone | **Under 100 ms per plugin** for onLoad + onEnable on the server thread, median of 3 boots, with the family installed. **Under 150 ms** alone on a fresh server, for the first plugin that loads the Kotlin stdlib (Paper spends about 140 ms on the stdlib's first classes, from #140) | CI boot on Paper 1.21.11 with the log4j pattern here |
| **Idle game-thread cost** | 0 for 9 plugins; xBackup 0.012 ms, xLimbo 0.002 ms | **Under 0.001 ms a tick per plugin** (no sample in 90 s at 1 ms) on the server thread, and **under 0.5 ms of CPU a second** on all its other threads together (xBackup is 1.3–3.5, xMoney 0.5–1.0 today) | 90 s profile, no players |
| **Under bot load** | 0.001–0.010 ms a tick per plugin, 0.023–0.032 ms for all 11, with 10 bots | **Under 0.05 ms a tick per plugin and 0.25 ms for the family**, server thread, 10 bots doing the above; off-thread under 2 ms of CPU a second per plugin. The roadmap's 0.5 ms would let the family use a fifth of a 20 TPS tick, about 15 times what all 11 use today | 120 s profile with `bots/load.js` |
| **Heap after load** | +1.6 to +2.5 MB for 9 plugins; xRewind +9.5 MB; xLimbo +44 MB (its world). Metaspace 0.5–1.0 MB | **Under 4 MB per plugin** after two full GCs, not counting chunks of worlds the plugin creates on purpose (xLimbo's world counted separately), and **under 1.5 MB of metaspace**. After M0, a change that grows either by more than 10% (or 0.5 MB, whichever is larger) fails | `jcmd` after startup, against a no-plugin run of the same build (repeatable to 0.4 MB) |
| **Jar size** | 163–483 KB a plugin, 3.4 MB for all 11 (Java, core shaded) | **No bigger than today's jar** for each Bukkit/Paper jar (stdlib fetched; R8 takes 14–19% off what's left). Where the stdlib is shaded (Sponge, and Fabric only where fabric-language-kotlin isn't used), **today's size + 300 KB**: R8 left 269 KB of a 1,966 KB jar with the stdlib and Cloud in it | Jar size in CI against a table checked into the repo |

Today's per-plugin jar limits, then: xBackup 483 KB, xBans 349, xLimbo 185, xMoney 369, xPlots 290, xRewind 327, xStats 399, xTeleport 293, xUtilities 163, xVote 320, zChat 189 (KB).

Why these and not the roadmap's first guesses:
- **Startup 100 ms holds,** but only because the family amortises the warm-up: alone, 8 of 11 plugins are over it today. What breaks it is synchronous database opening, which the rewrite already moves off the server thread ("disk and network work is off it"). The extra 50 ms for the first Kotlin plugin is the price of the stdlib's class loading; it is paid once per server, not per plugin.
- **"~0 ms idle" can be a number:** nine plugins already have no sample at all in 90 s. 0.001 ms is the profile's floor, and it is what xBackup's per-tick check fails today.
- **0.5 ms under load is far too loose** against today's 0.01 ms, and `/mspt` itself is only good to about ±0.4 ms between runs, so a budget near 0.5 ms could not even be checked with it. 0.05 ms is five times today's worst and is measured by the profile, which is good to about 0.001 ms.
- **Heap: "10% growth fails" works** because the measurement is repeatable to 0.4 MB on a 212 MB heap, but 10% of 2 MB is 0.2 MB, so it needs a floor. A fixed 4 MB limit catches xRewind's kind of table (9.5 MB) at review time.
- **Jar size "near today's"** becomes "no bigger" for Bukkit and Paper, where Kotlin costs nothing in the jar because the stdlib is fetched.

## What this changes in ROADMAP.md

Replace the budgets table with the "Proposed budget" column above, and add that budgets are measured on Paper 1.21.11 with the family installed, with async-profiler for tick costs and `jcmd` for heap, as in `sandbox/m0/budgets`.
