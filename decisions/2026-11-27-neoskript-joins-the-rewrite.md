# NeoSkript joins the rewrite: in this repo, in Kotlin, on the shared core (2026-11-27)

NeoSkript (xena-studios/neoskript, a drop-in Skript replacement on Paper and Folia, 0.1.0-beta.1) gets what the plugins get from the rewrite: every server the plugins run on, translations, the core, one site per product and one release to every store. What was decided:

- **It moves into this repo, under `neoskript/`.** The core is shaded into each plugin and never published (decisions/2026-10-21-the-core-is-shaded-into-each-plugin.md). Keeping NeoSkript apart would mean publishing the core as a versioned library while the rewrite still changes it every few days. In one repo a core change and the NeoSkript change that needs it are one pull request, and NeoSkript gets the boot matrix, budgets, release pipeline, stores and Maven Central publishing as they are.
- **All Kotlin.** About 1,200 Java files are converted. Kotlin Multiplatform lets the parser and type checker live in shared code built for the JVM and `wasmWasi`, which serves the website's script checker (in place of TeaVM), the language server, the CLI and Pumpkin from one source. Kotlin doesn't touch scripts' own speed: they are compiled to bytecode by the ASM compiler, which stays on the JVM.
- **Converted in place, one module at a time, and always releasable.** Kotlin and Java mix in one module, so `main` never holds a half-built NeoSkript. Every pull request keeps Skript's own test suite (853 tests) and the differential tests against real Skript at 100%, and the benchmarks no slower. So NeoSkript keeps shipping betas from `main` all the way through: no `maintenance` branch, and it isn't held for the plugins' release milestone.
- **Each area is converted once.** The standard library (39k lines in `neoskript-paper`) moves onto the core's platform interfaces and into Kotlin in the same pull request, area by area (events, entities, items, blocks, inventories), never twice.
- **Bytecode follows the server.** The roadmap's Java 8 for the Bukkit jar applies (Spigot 1.13 to 1.16.5 won't start on Java 17), and scripts are compiled for the server's own Java: Java 8 class files there, with nothing the generated code needs from Java 9 or later, and Java 21 or newer where the server runs it.
- **Item, block and entity tables are generated** from Minecraft's own data for each version, as xMarket's legacy names were (decisions/2026-11-27-xmarket.md), so 1.8.8 to 26.x isn't mapped by hand. Scripts written for 1.8's Skript get a mode for its aliases.
- **Testing without Skript.** Real Skript on Paper stays the reference. On every other platform the test harness runs the same scripts on Paper and on that platform and compares what happens.
- **SkBee and other Skript add-ons are Bukkit plugins,** so the compatibility layer for them is Bukkit-family only. NeoSkript's own add-ons (db, gui, yaml, web, proxy) go everywhere through the core.
- **Pumpkin can't run JVM bytecode,** so there NeoSkript runs scripts with an interpreter over the parsed scripts, in the shared code built for Wasm.
- **Budgets.** NeoSkript's own startup is held to the plugins' 100 ms; loading scripts is measured on its own, against Skript's (1.7 s for the 6,000-line benchmark today). Today's jar is 2.2 MB, against the 500 KiB target. It gets one jar per platform and ASM fetched like the Kotlin stdlib, and what stays over is recorded with numbers in N2, as the plugins record theirs.
- **The order: N0 and N1 now, N2 onward after M15.** N0 and N1 stay inside `neoskript/`, because the engine has no platform code, so they run beside the plugins' milestones without touching the core. N2 onward builds NeoSkript's game model on the core and adds to it. xRewind (M12, blocks), xPlots (M13, regions) and xLimits (M14, entities and redstone) need the same pieces, so they shape them first and NeoSkript builds on what they proved.

| | Milestone | Exit |
|---|---|---|
| N0 | The move and the spike: history under `neoskript/`, its own Gradle build and path-filtered CI at first, website and editor deploys moved, issues transferred, the old repo archived. Spike: Java 8 class files from the compiler on Spigot 1.8.8; about 20 syntaxes through a neutral game model on Paper and Fabric; the parser built for `wasmWasi` | CI green here; the spike's results recorded |
| N1 | The engine in Kotlin: API, parser and type checker (shared code), runtime, compiler, CLI, language server, the website's checker | Skript's suite and the differential tests at 100%, benchmarks no slower |
| N2 | The standard library and add-ons in Kotlin on the core, Paper and Folia: config, `lang/`, commands, scheduler and bStats from the core, so translations too | As N1, and within budget |
| N3 | Spigot, CraftBukkit and the Paper forks, today's versions | The boot matrix; the differential tests on Spigot |
| N4 | 1.16.5, then 1.13, then 1.8.8 | Each floor boot-tested and compared against Paper |
| N5 | Fabric and Quilt | The differential tests against Paper |
| N6 | Velocity and BungeeCord: the proxy's part of the language (commands, chat, server switching, network variables) | NeoSkript 1.0 |

Then, as minors, each after the core's platform: NeoForge, Forge 1.20.1 and Sponge (M16); Pumpkin and Vine, on the interpreter (M17); Minestom, as a library (M18); Hytale, with syntax of its own, since it's another game (M19).
