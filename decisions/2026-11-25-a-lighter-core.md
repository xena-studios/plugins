# A lighter core: fewer lambda classes, sealed interfaces, debug made on first use, startup lists recorded on a restart (2026-11-25)

zChat 4.0's metaspace was 1.69 MB against the 1.5 MB a plugin gets, so the Boot workflow's `budgets` job only reported it, and xUtilities' startup was report-only too (#177). Measured first, on Paper 26.3 in a Linux container on two cores (`scripts/budgets/check.sh`, whose metaspace matched CI's to the kilobyte), with `-Xlog:class+load` and `VM.classloader_stats`, the R8 mapping to read the classes in their own names.

**What the metaspace was.** zChat's class loader held 577 classes after startup. About 170 were not classes from the jar at all but the ones the JVM makes for each Kotlin lambda as it's first made (`LambdaMetafactory`'s hidden classes, in the plugin's own loader): about 1.5 KB of metaspace each (measured: 100 lambdas, 149 KB), so a quarter of a megabyte. About 40 more were loaded only by the bytecode verifier, which loads a class to check it's a subclass of what a method returns, unless that type is an interface. 20 were in the startup list but not needed by a restart: the list was recorded on the first boot, which writes the config and lang files. And the debug report, its paste service and health checks loaded at every start, though they run only on `/<plugin> debug`. The jar's own code that a running plugin uses (commands, config, i18n, text, the family) is most of the rest and stays.

What changed, in the core, so every plugin gets it:

- **The command DSL is inline.** `commands`, `root` and `literal` run their blocks in place, so declaring a tree makes no lambda objects; only each `executes` handler is one. zChat's and xUtilities' own helpers that added a root from a config section were local functions (which can't be inline) and are now inline members.
- **`locked` is inline**, and so are the run-now helpers on startup paths (`ModuleContext.guard`, `runContained`, the event bus's list change, the record store's change): `locked` had 79 call sites, each its own lambda class once reached.
- **No lambdas per value in tables every plugin loads.** `ColumnType` (the player cache's file) told its built-in types apart by two lambdas each: now by a number, with `nullable` and `map` as before. `Formats`' plural rules and date orders are a number and a pattern (`{d} {M} {y}`), not 13 lambdas. `ConfigType`'s built-in scalars are one class. zChat's three config types are one class.
- **Sealed interfaces where the subclasses are only results.** `CommandResult`, `SqlValue` and `DebugResult` are sealed interfaces, not sealed classes: the verifier no longer loads all ten command results, five value kinds and three report outcomes as the plugin starts. Nothing else about them changed.
- **`/<plugin> debug` and `forget` make what they run on the first time they run.** `AdminCommands` takes `debug: () -> Debug` and `playerData: (() -> PlayerDataRegistry)?`, kept once made. A plugin passes each as `object : () -> Debug { ... }`, not a lambda: the JVM links a lambda as it's made, which loads the class its function returns, and an object's method isn't linked until it runs. Checked on a real server: `debug` (every boot check runs it) and `forget` work as before.
- **Startup lists are recorded on a restart, with the jar's own list left out.** `check.sh --record-startup` now boots once more after the first boot, with copies of the jars without `META-INF/xena/startup-classes.txt`, and records that boot; `boot-proxy.sh --record-startup` records an extra, untimed boot the same way. Recording with the old list in the jar loaded every class it named ahead and recorded it again, so a list never lost a class. Every list was recorded again (zChat's 678 lines to 654, xUtilities' 641 to 614).

| Paper 26.3, two cores, `check.sh` (startup: median of 5) | Before | After |
|---|--:|--:|
| zChat: metaspace, classes in its loader | 1.687 MB, 577 | **1.438 MB**, 464 |
| xUtilities | 1.487 MB, 516 | **1.259 MB**, 422 |
| xSample | 1.44 MB, 538 | **1.24 MB**, 453 |
| zChat startup | 67.8 ms | 63.4 ms |
| xUtilities startup | 76.3 ms | 77.6 ms |
| xSample startup | 69.1 ms | 59.9 ms |

So the `budgets` job fails on every rewritten plugin's startup and metaspace again (no `--report-only` left). CI's runners measured startup at about 1.5 to 2 times the container's.

**Then the startup itself.** The first CI run of the above failed xUtilities' startup: 150.6 ms against 150 (onEnable 134.2), with xSample's onEnable at 88.3. Profiled in the same container with async-profiler (wall clock, the server thread, from onEnable), xUtilities' enable was mostly not its commands:

- **Half of it was reading `config.yml`, three times.** The 2.x messages check, the schema and the link commands each parsed the whole file, on the server thread, with a YAML parser that hasn't been compiled yet. zChat read its file four times (1.x, 2.x/3.x, the schema, the groups). core-jvm's `RememberingYaml` keeps the last text and what it read as (the nodes are immutable): the `ConfigFile` and the plugin's own reads go through it, so the file is parsed once, and it lets go after the load.
- **Bukkit's permissions were added one at a time** (7%). Bukkit works out the console's permissions again after each add, so a plugin with a hundred nodes paid for that a hundred times. Paper has `addPermissions(List)`, which does it once: `BukkitPermissions` uses it where it's there, and adds them one by one on Spigot.
- **Reflection over CraftServer** (6%): `getCommandMap` was looked up on CraftServer's class, which has more than a thousand methods, and `syncCommands` was found by listing all of them. Paper's `Server` interface has `getCommandMap`; `syncCommands` is looked up the first time a reload needs it, since at startup the server sends the commands itself.
- **bStats started in onEnable** (5%): reading its config and loading its classes. It starts on the next tick now, after the server's startup (it first sends after minutes), and a plugin disabled before then never starts it.
- **Every plugin with a proxy channel parsed all of `config/paper-global.yml`** to find Velocity's secret: 27% of xSample's enable on Paper 26.3, 38% on 1.21.11. `ProxySecret.paper` reads its `proxies:` block alone (the whole file if it has no such line).

Registering the ~50 commands, their aliases, the displaced vanilla commands and the command sync were a few per cent together, so they stay as they are.

| Paper 26.3, `check.sh` (startup: median of 5) | Container, before | Container, after | Plain (M-series), before | Plain, after |
|---|--:|--:|--:|--:|
| xUtilities | 84.5 ms | **55.6 ms** | 60.0 ms | **46.9 ms** |
| xSample | 57.3 ms | **48.8 ms** | 35.9 ms | **29.9 ms** |
| zChat | 79.3 ms | **58.9 ms** | 53.4 ms | **40.0 ms** |

The container's boots vary by about ±10 ms; the plain runs by about 2 ms. Metaspace is 1.265, 1.252 and 1.448 MB (was 1.261, 1.242, 1.426), jars 2–3 KiB bigger. The startup lists were recorded again: bStats' classes are out, the new reader's are in.

**The nightly BotMark run on Paper 1.21.11** (xSample 208.5 ms: server 16, onLoad 37.7, onEnable 156) is mostly Paper 1.21.11, not the plugin. In the container, the same jar took about 210 ms on 1.21.11 against 57 on 26.3, and about the same on Java 21 and 25. A JFR timeline of the enable showed the server thread using only about 6–7 ms of CPU on either version. The rest was waiting for one of the two cores, behind the JIT compiler threads (74 ms of CPU in xSample's 97 ms enable on 1.21.11, 34 in 36 on 26.3) and the plugin's own startup-class preloader. On 1.21.11 the preloader takes 287 ms of CPU against 88 and is still running when the plugin enables. Its loader and onLoad are 2.5 times slower there too, before any of the plugin's code runs. The `paper-global.yml` parse was the part that was ours, and it's fixed here (1.21.11: about 233 to 214 ms in the container). The rest would take a separate budget for that cell, or the preloader stopping once the plugin enables, which wouldn't save CPU on two cores. Neither is done here.

**Jars are about the same size** (zChat 615 to 612 KiB, xUtilities 513 to 509, Fabric's within 2 KiB either way): R8 already left out what a plugin can't reach, and what it keeps is code the plugin runs, so these changes moved metaspace, not bytes. The budget lines are kept where they were, each already within about a tenth of its jar, and zChat's Bukkit line goes from 675 to 673.

**Tried and not taken.**
- Compiling Kotlin's lambdas as classes (`-Xlambdas=class`, `-Xsam-conversions=class`) hoping R8 would merge them: R8 doesn't merge them for class-file output, and zChat's jar went from 541 to 834 entries and 615 to 752 KiB.
- Moving Spigot's chat bridge out of the class that picks it, so Paper's verifier wouldn't load it: R8 inlined the move back. The same goes for any change that only works while R8 keeps two methods apart, so the Bukkit events zChat's API bridge names (five classes) still load as it starts.
- Delaying the update checker's first check so its classes load later: it runs at startup on purpose, and they'd load anyway.
