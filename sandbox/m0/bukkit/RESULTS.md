# M0 spike: Kotlin on Bukkit, 1.8.8 to latest (results)

Part of #121. Everything below comes from servers booted for this spike on 2026-11-23 (macOS arm64,
`-Xmx1G`, offline mode, flat world, fresh folder per run unless it says "cached"). Nothing here is
wired into the root build.

## What's in this folder

| | |
|---|---|
| `loader/` | Java, compiled to **Java 8 bytecode**: `Bootstrap` (the plugin's main class) and `Libraries` (fetch, SHA-256 check, and four ways to make jars visible). Shaded and relocated into each plugin. |
| `hello/` | Kotlin plugin, stdlib only, one `/hello` command, `provides: [HelloProvided]`. |
| `hellocloud/` | Kotlin plugin on Incendo Cloud 2 (`cloud-paper` 2.0.1, `LegacyPaperCommandManager`), Adventure and MiniMessage 4.26.1, `adventure-platform-bukkit` 4.4.1 on Spigot. `/hellocloud [name]` and `/xbench [count]`. |
| `api/`, `java-consumer/` | A Kotlin API and a Java 17 program calling it, compiled with `-Xlint:all -Werror`. |
| `buildSrc/` | `FetchedLibraries`: writes `META-INF/m0/libraries.txt` (every jar, transitively, with its SHA-256 pinned at build time and a probe class). |
| `boot.sh`, `matrix.sh`, `bot/` | Boot one server, run console commands, stop it; the startup matrix; a mineflayer player for the benchmark. |

Build: `./gradlew build` (JDK 17+); `-Pm0.jvm=16` or `-Pm0.jvm=1.8` sets the Kotlin bytecode
(and `-Xjdk-release`). Boot: `SERVERS=<folder outside the repo> ./boot.sh <run> <server.jar> <java> --run "hello" build/.../Hello.jar`.

Versions: Kotlin 2.4.21, Gradle 9.8.2, Spigot from BuildTools (1.8.8, 1.12.2, 1.13.2, 1.14.4,
1.15.2, 1.16.5, 1.21.11; BuildTools itself on Java 8, an arm64 Zulu 8, since the installed
Temurin 8 is x86_64 and this Mac has no Rosetta), Paper 1.8.8-445, 1.16.5-794, 1.21.11-132,
Folia 26.3-27 (latest), PandaSpigot 1.8.8 build 227. Java: Temurin 11.0.32, 17.0.20, 21.0.12,
25.0.4, Zulu 8u514, Zulu 16.0.2.

## Summary

1. **Yes, with one big correction to the roadmap:** a Kotlin plugin with the stdlib fetched, not
   shaded, loads and runs its command on Spigot 1.8.8, PandaSpigot 1.8.8 (Java 17 and 21), Spigot
   1.16.5 (`libraries:`), Paper 1.21.11 and Folia 26.3. **But Spigot 1.13.2–1.16.5 refuse to start
   on Java 17 at all** ("Unsupported Java detected (61.0). Only up to Java 12/13/14/16 is
   supported.", no override flag), so Java 17 bytecode can never run on Spigot 1.13–1.16.5. Kotlin
   compiled to **Java 8 bytecode** (`jvmTarget 1.8`, `-Xjdk-release=1.8`) runs on every server tried,
   from Spigot 1.8.8 on Java 8 to Folia on Java 25.
2. **Yes:** Cloud 2 and `adventure-platform-bukkit` + MiniMessage work on Spigot 1.8.8 and 1.21.11,
   fetched the same way. One send costs ~8.4 µs on Spigot (both versions) against ~0.4 µs with
   Paper's native Adventure, on the main thread.
3. **Yes:** compiles with `-Xlint:all -Werror` and reads like a Java API. Pitfalls listed below
   (`@JvmOverloads` is refused on interface methods; value classes mangle names; returned lists are
   mutable).
4. **`provides:` arrived in Spigot 1.15.2** (Bukkit commit 9f2fd967a4f, 2020-03-15). 1.8.8–1.15.1
   ignore it.
5. **Jars:** Hello 23 KB, HelloCloud 31 KB. **Startup (cached libraries):** 24–67 ms for Hello
   everywhere except Paper 1.21.11 (~140 ms, all in the first stdlib class loads through Paper's
   library class loader).

## 1. The stdlib, not shaded, on each server

The main class is Java (8 bytecode), never touches Kotlin, and loads the Kotlin `Delegate` by name
after `Libraries.ensure()`. On servers with `libraries:` the server has already put the stdlib on
the class path and nothing is fetched. Otherwise each jar is downloaded once (Google's Maven
Central mirror, then Maven Central) into `plugins/<name>/libraries/`, checked against its pinned
SHA-256, and made visible with one of four strategies (`-Dm0.loader=`):

| Strategy | How | Result |
|---|---|---|
| `reflect` | `URLClassLoader.addURL` via `setAccessible` | Java 8: works, silent. Java 11: works with "An illegal reflective access operation has occurred". **Java 16, 17, 21, 25: refused** (`InaccessibleObjectException: module java.base does not "opens java.net" to unnamed module`). |
| `lookup` | the same `addURL`, through `MethodHandles.Lookup.IMPL_LOOKUP` read with `sun.misc.Unsafe` | **Works on Java 11, 16, 17, 21, 25**, on every Spigot/Paper/PandaSpigot version tried. Java 25 prints the JDK's own warning to stderr: `sun.misc.Unsafe::staticFieldBase has been called by ...Libraries ... will be removed in a future release` (JEP 471/498: a later JDK will throw instead). |
| `global` | wrap Bukkit's global class cache (`JavaPluginLoader.classes`) so it also asks a library loader of ours; no JDK internals | Works on 1.8.8 (Java 25, no warning), 1.12.2 (17), 1.13.2 (11). **Breaks on 1.15.2** (`ClassCastException: Libraries$Shared cannot be cast to PluginClassLoader`: 1.15 casts the found class's loader) and **1.21.11 has no such field**. Too fragile. |
| `isolated` | no injection: the plugin's own Kotlin classes and the libraries load in a child-first class loader (LuckPerms' jar-in-jar idea) | Works everywhere tried (1.8.8 on 17, 1.21.11 as the fallback). But the plugin's classes then aren't the ones its `PluginClassLoader` would give other plugins, so a Kotlin API other plugins call can't live there. |

`auto` (the default after this spike): `reflect` on Java 8, `lookup` on 9+, `isolated` if both fail.

Per server (Hello + HelloCloud together, `/hello` and `/hellocloud` from the console; logs in each
run folder; "first" = libraries downloaded, "cached" = second boot of the same folder):

| Server | Java | Bytecode | Stdlib comes from | Works | Hello startup first / cached |
|---|---|---|---|---|---|
| Spigot 1.8.8 | 8 | 17 | — | **refused**, with the bootstrap's own message: `Hello needs Java 17 or newer; this server runs Java 1.8.` (the main class is Java 8 bytecode so it can say so; before that fix it was an `UnsupportedClassVersionError` stack trace) | — |
| Spigot 1.8.8 | 8 | **8** | fetched, `reflect` | yes | 969 / — ms |
| Spigot 1.8.8 | 17 | 17 | fetched, `lookup` | yes | 952 / 38 ms |
| Spigot 1.8.8 | 21 | 17 | fetched, `lookup` | yes | 885 / 38 ms |
| Spigot 1.8.8 | 25 | 17 | fetched, `lookup` (Unsafe warning) or `global` (none) | yes | 960 / — ms |
| Paper 1.8.8-445 | 17 | 17 | fetched, `lookup` | yes | 711 / 37 ms |
| PandaSpigot 1.8.8-227 | 17 | 17 | fetched, `lookup` | yes | 915 / 40 ms |
| PandaSpigot 1.8.8-227 | 21 | 17 | fetched, `lookup` | yes | 670 / 24 ms |
| Spigot 1.12.2 | 17 | 17 | fetched, `lookup` | yes | 907 / 37 ms |
| Spigot 1.13.2 | 17 | 17 | — | **server refuses Java 17**: "Only up to Java 12 is supported." | — |
| Spigot 1.13.2 | 11 | **8** | fetched, `lookup` | yes | 881 ms |
| Spigot 1.14.4 | 17 / 11 | 17 / **8** | fetched (`reflect` with warning; `lookup` silent) | 17: refused ("up to Java 13"); 8 on Java 11: yes | 870 ms |
| Spigot 1.15.2 | 17 / 11 | 17 / **8** | fetched | 17: refused ("up to Java 14"); 8 on Java 11: yes | 778 ms |
| Spigot 1.16.5 | 17 | 17 | — | **refused** ("Only up to Java 16 is supported.", unconditional return in `Main`, no flag) | — |
| Spigot 1.16.5 | 16 | 16 | `libraries:` (server's `URLClassLoader`) | yes | 33 / 67 ms |
| Spigot 1.16.5 | 11 | **8** | `libraries:` | yes | 27 ms |
| Paper 1.16.5-794 | 17 | 17 | `libraries:` | yes, only with `-DPaper.IgnoreJavaVersion=true` | 31 / 50 ms |
| Spigot 1.21.11 | 21 | 17 and 8 | `libraries:` | yes | 32 / 28 ms |
| Paper 1.21.11-132 | 21 | 17 | `libraries:` (Paper's `BytecodeModifyingURLClassLoader`) | yes | 165 / 142 ms |
| Folia 26.3-27 | 25 | 17 | `libraries:` | yes | 29 / 24 ms |

Things found along the way:

- **The Spigot Java caps** (from `Main`): 1.13.2 → 12, 1.14.4 → 13, 1.15.2 → 14, 1.16.5 → 16. 1.8.8
  and 1.12.2 have no check (1.8.8 ran on 17, 21 and 25; 1.12.2 on 17). Paper 1.16.5 has the same cap but honours
  `-DPaper.IgnoreJavaVersion=true`.
- **On 1.8.8–1.14.4 every plugin shares the first plugin's fetched stdlib.** Bukkit's
  `PluginClassLoader` asks the global class cache before its own jar, so HelloCloud logged
  `kotlin-stdlib from kotlin-stdlib-2.4.21.jar (PluginClassLoader)`, Hello's copy, and fetched only
  Cloud. Our plugins must pin the same stdlib version (the core's), and a third-party plugin that
  shaded an older Kotlin unrelocated can win. A startup check of `KotlinVersion.CURRENT` against
  the version we were built with is cheap and worth having.
- **Probe per jar, and the group probe must come from the server.** A group is skipped only when
  its probe class comes from the server (not a plugin's loader); otherwise each jar is checked by a
  class of its own. Needed because the stdlib is visible from another plugin on 1.8.8, and because
  PandaSpigot 1.8.8 bundles Adventure (with MiniMessage and native `Audience` senders), Paper
  1.16.5 bundles Adventure without MiniMessage, Paper 1.21.11 bundles 4.26.1 and Folia 26.3
  bundles **Adventure 5.2.0** (the plugin, compiled against 4.26.1, worked against it).
- **`libraries:` on Spigot 1.16.5 arrived in May 2021** (Bukkit commit 146a7e4bd76, SPIGOT-5345), so
  only late 1.16.5 builds have it; BuildTools builds the last one. It downloads from
  `repo.maven.apache.org` and pulls `org.jetbrains:annotations:13.0` along with the stdlib. Spigot
  1.16.5 logs a few `Invalid cookie header` WARN lines from Maven Central's CDN while doing so.
- **Paper 1.21.11 is the slow one:** Hello's `onEnable` is ~120–140 ms on every boot, and timing the
  steps shows it's all in the first use of a few stdlib classes (`report()`: `KotlinVersion`, a data
  class, `joinToString`), i.e. Paper's `BytecodeModifyingURLClassLoader` rewriting library classes
  as they load. Folia 26.3 uses a plain `URLClassLoader` and takes ~20 ms for the same code.
- `paperweight-mappings-namespace: mojang` in the manifest stops Paper 1.21.11 remapping the jar at
  startup (it logged `Done remapping plugin ... in 91ms` without it).
- The bootstrap reads the Java version it needs from the delegate class file's major version, so
  the same message works for 8, 16 or 17 builds.

## 2. Cloud 2 and Adventure

Same plugin everywhere. Cloud 2 (`cloud-paper` 2.0.1 → `cloud-bukkit` 2.0.1, `cloud-core`/
`cloud-services` 2.1.0, geantyref; 858 KiB) comes from `libraries:` on 1.16.5+ and the bootstrap
below that. `adventure-platform-bukkit` 4.4.1 with Adventure 4.26.1 (17 jars, 952 KiB, the server's
Gson used, which is 2.2.4 on 1.8.8) and MiniMessage 4.26.1 (189 KiB) are fetched only where the
server has no Adventure: never on Paper 1.21.11, Folia or PandaSpigot. The send path (native
`Audience` or `BukkitAudiences`) is picked once at enable.

| Server | Cloud | Text | `/hellocloud` |
|---|---|---|---|
| Spigot 1.8.8 (Java 17, 21, 25; 8 with Java 8 bytecode) | ok, commands registered at runtime | adventure-platform-bukkit | ok |
| PandaSpigot 1.8.8 | ok | native (PandaSpigot bundles Adventure + MiniMessage) | ok |
| Spigot 1.12.2–1.15.2, 1.16.5 | ok | adventure-platform-bukkit | ok |
| Paper 1.16.5 | ok | **fails**: Paper 1.16.5 has an old Adventure and no MiniMessage; MiniMessage 4.26.1 added to the plugin needs `net.kyori.adventure.builder.AbstractBuilder`, which the server's Adventure lacks, and the server's copy always wins (parent first) | — |
| Spigot 1.21.11 | ok | adventure-platform-bukkit | ok |
| Paper 1.21.11, Folia 26.3 | ok (Folia: console command ran) | native | ok |

**One themed message** (`<#7c5cff>xUtilities</#7c5cff> <dark_gray>»</dark_gray> <gray>Teleported to
<white><target></white>.</gray>`), parsed once, sent 5,000 times to one connected mineflayer player
on the main thread; median of five rounds after a warm-up round, second run of `/xbench` (the first
run's numbers in brackets). The bot received every message (60,001 or 180,001 per run).

| Server, Java | Path | Send, ns | MiniMessage parse, ns |
|---|---|---|---|
| Paper 1.21.11, 21 | native Adventure | **382** (468) | 16,282 |
| PandaSpigot 1.8.8, 21 | native Adventure | **239** (292) | 14,357 |
| Spigot 1.21.11, 21 | adventure-platform-bukkit | **8,410** (15,218) | 13,000 |
| Spigot 1.21.11, 21 | `BaseComponent[]` made once, `spigot().sendMessage` | 12,677 | |
| Spigot 1.21.11, 21 | legacy `§` string made once, `sendMessage(String)` | 4,603 | |
| Spigot 1.8.8, 17 | adventure-platform-bukkit | **8,384** (10,216) | 14,330 |
| Spigot 1.8.8, 17 | `BaseComponent[]` made once | 73 (192) | |
| Spigot 1.8.8, 17 | legacy string made once | 3,815 | |

So a send through `adventure-platform-bukkit` costs ~20× Paper's native send on the main thread
(~8 µs against ~0.4 µs; Paper does its serialization later, on the network thread). It's still
small (120 sends per millisecond), but it's per player per message, so broadcasts to 100 players
cost ~0.8 ms on Spigot. Drawing a message once per language into the server's own form is cheaper:
on 1.8.8 a cached `BaseComponent[]` costs almost nothing (the packet holds it and serializes off
thread), on 1.21.11 a cached legacy string is half the cost. MiniMessage parsing is 13–18 µs a
message, which confirms "parse once per language" is the right rule.

## 3. A Java plugin calling a Kotlin API

`api/Wallet.kt` (interface, `companion object` with `@JvmStatic fun api()`, `CompletableFuture`
returns, an event delivered to a `java.util.function.Consumer`, data classes, a sealed interface,
a `@JvmOverloads` constructor) and `java-consumer/JavaConsumer.java`, built with
`-Xlint:all -Werror` and run (`./gradlew :java-consumer:run`):

```
event: 0 -> 1000, player true
deposits: Transaction(player=..., before=0, after=1000, reason=api) / vote -> 1500
top list accepted an add
transfers: transfer/true, quiet/false
refused: too poor
null check: Parameter specified as non-null is null: method co.xenastudios.m0.api.MemoryWallet.balance, parameter player
greet: Hello, Steve
kotlin lambda: 1
undeclared checked exception: java.io.IOException: not there
```

It compiles with no warnings and reads like Java: `Wallet.api()`, `wallet.deposit(id, 1000L).get()`,
`event.getDifference()`, `event.isPlayer()`, `new Transfer(from, to, 5L)`, `outcome instanceof
Outcome.Refused refused`. Kotlin emits `PermittedSubclasses` for the sealed interface at JVM 17, so
Java 21 `switch` can be exhaustive over it. Pitfalls (`javap` of the API shows each):

- **`@JvmOverloads` is not allowed on interface methods.** A default argument there (`top(limit: Int
  = 10)`) leaves Java with `top(int)` plus a public static `top$default` in autocomplete. Write the
  overload as a second, default method (as `deposit` does) or keep defaults off API interfaces.
- **Value classes**: `fun pay(player: UUID, units: Units)` compiles to `pay-P6Xd7Vs(UUID, long)`,
  which javac can't call. Keep them out of signatures (the roadmap already says so).
- **`object` / companion without `@JvmStatic`**: `Pitfalls.INSTANCE.plain()`; the `Companion` field
  and `$$INSTANCE` show up in Java autocomplete either way.
- **Kotlin function types**: a `(BalanceChanged) -> Unit` parameter makes Java write
  `return Unit.INSTANCE;` and import `kotlin.Unit`. Use `fun interface` or `java.util.function`.
- **No `@Throws`**: Java can't catch the checked exception by type (`catch (IOException e)` doesn't
  compile).
- **Returned collections are mutable at runtime**: Kotlin's `List` from `map`/`take` is an
  `ArrayList`, and Java added to it. Return copies or `Collections.unmodifiableList`.
- **`suspend`** becomes `Object later(Continuation)`. `internal` members are public in bytecode with
  mangled names.
- Default-argument functions without `@JvmOverloads` make Java pass every argument.
- Non-null parameters are checked: Java passing `null` gets a clear `NullPointerException` from the
  Kotlin side, which is what we want.
- Use `jvmDefault = NO_COMPATIBILITY` for a new API, so interface bodies are real default methods
  with no `DefaultImpls` classes.

## 4. `provides:`

`PluginDescriptionFile.getProvides()` and `SimplePluginManager`'s use of it appear first in
spigot-api 1.15.2 (checked with `javap` in every API jar from 1.8.8 to 1.16.5: absent in 1.8.8,
1.9.4, 1.10.2, 1.11.2, 1.12–1.12.2, 1.13.2, 1.14, 1.14.4, 1.15, 1.15.1). Bukkit commit 9f2fd967a4f
"#474: Add ability to set other plugin names as provided API so others can still depend on it",
2020-03-15, during 1.15.2. On booted servers, `getPlugin("HelloProvided")` (and HelloCloud's
`softdepend: [HelloProvided]`) gave `null` on 1.8.8, 1.12.2, 1.13.2, 1.14.4 and `Hello` on 1.15.2,
1.16.5, 1.21.11, Paper 1.16.5, Paper 1.21.11 and Folia 26.3.

So xMoney can stand in for Vault only from 1.15.2. Below that, plugins that check
`getPlugin("Vault")` or `depend: [Vault]` won't see it: xMoney there has to run beside a real Vault
and register its economy through Vault's `ServicesManager` the classic way.

## 5. Sizes and startup

| Jar | Bytes | Of which |
|---|---|---|
| Hello (Java 17 Kotlin) | 23,242 | uncompressed, the loader is ~20 KB (`Libraries` alone 14.9 KB) and the Kotlin code ~12 KB |
| Hello (Java 8 Kotlin) | 23,150 | |
| HelloCloud | 31,163 | |
| Fetched instead: kotlin-stdlib 2.4.21 | 1,809 KiB | what shading it would add to every jar |
| Cloud group / Adventure group / MiniMessage | 858 / 952 / 189 KiB | |

No R8 here; the loader will shrink once it's in the core.

Startup of the plugin itself (`onLoad` + `onEnable`, measured in the bootstrap): with libraries
already downloaded, Hello takes 24–67 ms everywhere except Paper 1.21.11 (124–154 ms, see above);
HelloCloud (Cloud + `BukkitAudiences`) 74 ms (PandaSpigot), 149 ms (Spigot 1.8.8), 175 ms (Spigot
1.21.11), 71 ms (Folia), 228 ms (Paper 1.21.11). The first boot adds the downloads: ~0.7–0.9 s for
the stdlib alone, 1.6–3.3 s for everything HelloCloud needs on Spigot, and on servers with
`libraries:` the server downloads before `onLoad`. Server "Done" times with and without Hello
differ by less than run-to-run noise (e.g. Spigot 1.8.8: 3.66 s baseline, 3.36 s first, 0.57 s
cached; Paper 1.21.11: 7.35 s baseline, 6.58 s cached).

## Recommendations for the roadmap

1. **Bytecode: compile the Bukkit jar (and whatever of the core it carries) to Java 8, not 17**:
   Kotlin `jvmTarget = 1.8` with `-Xjdk-release=1.8`. Kotlin needs nothing from Java 9+ to be Kotlin;
   the roadmap's reason for 17 (records, `java.net.http` in today's Java code) goes away with the
   rewrite. With Java 17 bytecode, **Spigot 1.13–1.16.5 can't run the plugins at all** (their
   `Main` refuses Java above 12–16, with no flag), which breaks the 1.13 floor of xStats, xRewind,
   xPlots and xLimits on Spigot and the Spigot 1.13.2 and 1.16.5 boot tests. Paper-only code (Folia
   schedulers, native Adventure, async teleports) can stay Java 21 in its own source set: a class's
   version only matters when it's loaded. If Java 8's JDK API is too small for the core, Java 11 is
   the next stop (Spigot 1.13.2–1.16.5 accept 11), at the cost of 1.8.8 servers still on Java 8.
2. **Keep the main class in Java 8 bytecode** whatever the rest is, so a too-old Java gets one clear
   line, not a stack trace.
3. **The loader's strategy:** `reflect` on Java 8, `IMPL_LOOKUP` on 9+, an isolated child-first
   loader as the last resort, and say in the docs that `--add-opens java.base/java.net=ALL-UNNAMED`
   is the fix if a future JDK refuses `Unsafe`. Only 1.8.8–1.16.4 servers need it for the stdlib;
   every Spigot needs it for Adventure, since `libraries:` can't be made Spigot-only.
4. **Decide per jar, not per group,** and treat "visible from another plugin" as not provided. Check
   `KotlinVersion.CURRENT` at startup on 1.8.8–1.14.4, where the first plugin's stdlib is everyone's.
5. **Text:** keep the plan of our own markup drawn to Adventure, and draw to the server's own form
   once per language on Spigot (legacy string or `BaseComponent[]`) rather than sending through
   `adventure-platform-bukkit` per player. Our renderer must use only old Adventure API on Paper
   1.16.5–1.18.1, whose bundled Adventure has no MiniMessage and can't be replaced.
6. **xMoney's `provides: [Vault]`** works from 1.15.2 only; below it, xMoney needs real Vault.
7. **Floors:** 1.8.8 holds for Spigot, Paper and PandaSpigot (Spigot 1.8.8 ran on Java 8, 17, 21 and 25; 1.12.2 on 17).
   The 1.13 floors hold only with Java 8 (or 11) bytecode. With Java 17 the effective Spigot floor
   for those plugins is 1.17, and 1.13–1.16.5 Paper needs `-DPaper.IgnoreJavaVersion=true`.
8. **Paper:** add `paperweight-mappings-namespace: mojang` to every jar's manifest, and watch the
   ~120 ms first-stdlib-load cost of Paper 1.21.11's library loader against the 100 ms startup budget
   (Folia 26.3 doesn't have it; Paper 26.x untested here).
9. **CI:** Spigot 1.16.5 boot tests need Java 16 (or 11), 1.13.2 needs Java 11, and BuildTools for
   1.8.8–1.16.5 needs a JDK 8 (on arm64 macOS that's Zulu or similar; Temurin 8 is x86_64 only).

## Not done

- Bungee, Fabric, Sponge: other parts of M0.
- CraftBukkit jars (BuildTools `--compile craftbukkit`) weren't built; they share Spigot's loader.
- No R8, so the jar sizes include the loader unshrunk.
