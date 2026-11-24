# Roadmap: the rewrite

Every plugin is rewritten once more, so the same plugin runs on every server software it reasonably can, in every player's language, with the same speed and size it has on Paper today. Tracked in the [rewrite tracking issue](https://github.com/xena-studios/plugins/issues?q=label%3Arewrite+label%3Amilestone), one milestone at a time; why each choice was made goes in [DECISIONS.md](DECISIONS.md) as it's settled.

## Goals

- **Every server it makes sense on:** Paper, Folia and their forks; Spigot, CraftBukkit and Bukkit down to 1.8.8; Fabric and NeoForge (server-side, players install nothing); Sponge; Velocity and BungeeCord for network features; Pumpkin; Hytale.
- **Fast, robust and light** on all of them, with budgets CI enforces. Paper keeps today's speed and jar sizes.
- **Translated**, each player in their own language.
- **Good developer APIs**, the same on every platform.
- **One family:** the plugins work together, and with the big third-party plugins.
- **One site**, plugins.xenastudios.co, its reference pages generated from the code.
- **One release** publishes every jar to every store.
- **One plugin at a time:** each is finished and released before the next starts.

Not planned: 1.7.10, Legacy Fabric (1.8.9), native Rust ports, a web config editor, paid tiers. Every feature stays free.

## Decisions

| | |
|---|---|
| Branches | `maintenance` holds today's versions and takes fixes only (released from there). `main` is the rewrite. |
| Versions | Each plugin's rewrite is its next major: xUtilities 3.0, zChat 4.0, xLimbo 3.0, the others 2.0; new plugins start at 1.0. A platform added later is a minor. Each API has its own semver. |
| Language | Kotlin. Plugin logic is Kotlin Multiplatform, built for the JVM and for `wasmWasi` (Pumpkin's Kotlin API is Kotlin/Wasm). Platform adapters are Kotlin/JVM. |
| Bytecode | Java 17 for the core and Bukkit builds (1.8.8–1.16 servers run on Java 17+). Java 21 for Fabric and NeoForge, Java 25 for Hytale. |
| Kotlin stdlib | Never shaded where it can be fetched: `plugin.yml` `libraries:` on Spigot 1.16.5+, the core's loader (SHA-256 pinned) below that, `fabric-language-kotlin` on Fabric, KotlinForForge on NeoForge. A shrunk copy elsewhere. |
| Text | Our own MiniMessage-style markup in `common`, drawn to Adventure on the JVM and to Pumpkin's text on Pumpkin. |
| Commands | Incendo Cloud 2 on the JVM; Pumpkin's commands on Pumpkin. |
| What a major needs | Paper, Folia, Spigot (down to the plugin's floor) and Fabric, plus a proxy where the plugin has network features. NeoForge, Sponge, Pumpkin and Hytale follow as minors. |
| Dropping a platform | Under 1% of bStats installs for six months, announced a major ahead. |

## Architecture

```
core/
  core-common        KMP: config schema, i18n, markup, SQL, formatters, event bus. Nothing from java.*
  core-jvm           Adventure bridge, JDBC, library loader, Cloud, importers
  core-api           what plugin APIs share: player refs by UUID, Location, events
  platform-bukkit    1.8.8 → latest, with Paper and Folia paths picked once at startup
  platform-fabric    server-side, several versions through Stonecutter
  platform-neoforge  likewise
  platform-sponge    API 8+
  platform-velocity, platform-bungee
  platform-pumpkin   Kotlin/Wasm, on pumpkin-api-kt
  platform-hytale    the Java 25 server API
<plugin>/
  api/      the public API, platform-neutral
  common/   KMP: all the plugin's logic
  bukkit/ fabric/ neoforge/ sponge/ velocity/ pumpkin/ hytale/   thin adapters
```

- `common` never imports `java.*` or a platform. CI builds the `wasmWasi` target from the first commit, so it can't creep in.
- Plugins reach each other only through their APIs. Each plugin relocates its own copy of the core, as now.
- A platform picks its implementations once, at startup, never per call.
- No NMS, no `kotlin-reflect`.

## Platforms per plugin

| Plugin | Bukkit floor | In the major | Later minors |
|---|---|---|---|
| xUtilities 3.0 | 1.8.8 | Paper, Folia, Spigot, Fabric | NeoForge, Sponge, Pumpkin, Hytale |
| zChat 4.0 | 1.8.8 | + Velocity, BungeeCord | NeoForge, Sponge, Pumpkin, Hytale |
| xMoney 2.0 | 1.8.8 | Paper, Folia, Spigot, Fabric | NeoForge, Sponge, Pumpkin, Hytale |
| xTeleport 2.0 | 1.8.8 | Paper, Folia, Spigot, Fabric | NeoForge, Sponge, Pumpkin, Hytale |
| xBans 2.0 | 1.8.8 | + Velocity, BungeeCord | NeoForge, Sponge, Pumpkin, Hytale |
| xVanish 1.0 (#109) | 1.8.8 | Paper, Folia, Spigot, Fabric | NeoForge, Sponge, Pumpkin, Hytale |
| xVote 2.0 | 1.8.8 | + Velocity, BungeeCord | NeoForge, Sponge, Pumpkin |
| xMarket 1.0 (#110) | 1.8.8, to confirm | Paper, Folia, Spigot, Fabric | NeoForge, Sponge, Pumpkin |
| xStats 2.0 | 1.13 | + Velocity | NeoForge, Sponge, Pumpkin, Hytale |
| xBackup 2.0 | 1.8.8 | Paper, Folia, Spigot, Fabric | NeoForge, Sponge, Pumpkin if its file access allows |
| xRewind 2.0 | 1.13 | Paper, Folia, Spigot, Fabric | NeoForge, Pumpkin |
| xPlots 2.0 | 1.13 | Paper, Folia, Spigot (WorldGuard) | Fabric and NeoForge with our own regions |
| xLimits 1.0 (#111) | 1.13 | Paper, Folia, Spigot, Fabric | NeoForge |
| xLimbo 3.0 | Paper 1.21.11 | Paper only, by design | A Velocity companion |

The floors are proposals; M0 confirms them.

## Fast, robust, light

**Fast.** Nothing blocks a game thread: disk and network work is off it, writes queue and go to the database in batches from one writer thread, reads come from memory. Work happens once, at load: configs become typed objects, messages are parsed once per language, permission results are cached per player until they change. Hot paths (every tick, event, chat message or block change) don't allocate what they can avoid, use the core's primitive maps, and leave early. No classpath scanning or reflection at startup. Every task goes through the core's `Scheduler`, so Folia's regions are always right.

**Light.** No runtime dependency beyond the Kotlin stdlib. Optional libraries are fetched only when a feature uses them (the MySQL driver only with `storage: mysql`). A module that's off loads no classes, registers no listeners and runs no tasks. Every jar is shrunk with R8.

**Robust.** Null-safety, and errors as typed results: no exception of ours reaches the server. A failing module turns itself off and says why; the rest of the plugin keeps running. A config that can't be read keeps the last good one. Files are written to a temporary file and renamed; database changes are transactions; migrations are versioned, so they can run again safely. Development builds throw on the wrong thread; release builds don't check. Parsers get property tests and fuzzing; a nightly soak test runs bots for hours; a chaos test cuts the database mid-run and checks nothing is lost.

**Budgets** (fail the build when exceeded; numbers settled in M0):

| | |
|---|---|
| Idle game-thread cost | ~0 ms a tick |
| Under bot load | under 0.5 ms a tick per plugin |
| Startup | under 100 ms per plugin |
| Heap after load | measured per plugin; more than 10% growth fails |
| Jar size | per plugin, near today's |

## Built once, in the core

**Translations** (from #117). `lang/<tag>.yml` in the jar, English as the fallback. `language: auto` follows each player's client; `default-language` is for the console and languages we don't ship. Parsed once per language; broadcasts drawn once per language; numbers and dates formatted for the reader. Launch languages de, es, fr, pt-BR, ru, pl and zh-CN, drafted by AI from a shared glossary and reviewed by native speakers; Crowdin later. CI fails on missing keys and broken tags.

**Developer APIs.** Platform-neutral and by UUID, so one API works everywhere, with small optional helpers per platform (`xmoney-api-bukkit`). Written in Kotlin and easy from Java: `@JvmStatic`, `@JvmOverloads`, no value classes or `suspend` in signatures, `CompletableFuture` for I/O. Events go on our own bus and, on Bukkit, are also fired as Bukkit events, so Skript and NeoSkript see them. `XMoney.api()` everywhere, also registered in each platform's services. Kotlin's ABI validation replaces `apiDump`; anything deprecated keeps working for a major. Every plugin has an API, xPlots included: read its state, change it behind cancellable pre-events, and register add-ons (vote reward types, price providers, stat counters). Published to Maven Central with Dokka docs.

**The family.** One optional shared database (a table prefix per plugin) and one change feed for networks. One theme and language in `plugins/Xena/`, which each plugin can override. One player cache: names, last seen, language, vanish. Integrations switch on by themselves when the other plugin is there:

| Plugin | Works with |
|---|---|
| xMoney | xPlots rent, xVote rewards, xMarket, balance placeholders in zChat and xStats |
| xBans | zChat (mutes), xVanish (staff), network-wide bans through the proxy |
| xVanish | hidden in zChat, xTeleport, xStats, xLimbo and every tab list |
| xTeleport | xPlots (plot homes), xLimbo (sends players back) |
| xRewind | xPlots (rollback inside a plot), xLimits (shared block and entity hooks) |
| xBackup | every plugin registers its data and pauses its database for a backup |
| xStats | every plugin adds stats through its API |

CI boots every plugin together on each platform and runs the integrations.

**Other plugins and mods.** LuckPerms, or the platform's permissions. PlaceholderAPI, MiniPlaceholders, Text Placeholder API. Vault and VaultUnlocked, Sponge's economy service, Common Economy API. NuVotifier, WorldGuard and WorldEdit, Geyser and Floodgate (Bedrock forms instead of chest menus), NeoSkript syntax, bStats. Importers in the core for EssentialsX, LiteBans, AdvancedBan, CoreProtect and others.

**Admin commands.** Under each plugin's own command; no shared command.

| Command | Does | In |
|---|---|---|
| `/<plugin> reload` | config and language files | every plugin |
| `/<plugin> debug` | health checks (database, dependencies, Folia), then a paste link of versions, platform, recent errors and the config with secrets removed | every plugin |
| `/<plugin> import <source>` | from another plugin, such as `/xbans import litebans` | plugins with importers |
| `/<plugin> migrate <sqlite\|mysql>` | moves data between storage types | plugins with a database |
| `/<plugin> forget <player>` | deletes one player's data | plugins that keep player data |

Each subcommand has its own permission under `<plugin>.admin`.

**Coming from today's versions.** The first start copies the old config and database aside, then migrates. Putting the old jar back restores that copy on its start, and the upgrade page says how. CI migrates real configs and databases from every release so far. A downgrade over migrated data says so instead of corrupting it.

**Updates.** Alpha, beta and release channels (Modrinth's version types); the update notice follows the channel the owner picked, in their language.

## Build, CI and releases

- **Build.** Gradle's configuration cache and build cache, with a remote cache in CI; only affected plugins and platforms build. A normal PR's CI under 3 minutes.
- **Tests.** `kotlin.test` on the JVM and Wasm; a fake platform for command logic; boot tests on real servers: Paper and Folia latest; Spigot 1.8.8, 1.13.2, 1.16.5 and latest; Fabric and NeoForge oldest and latest; Sponge; Velocity and BungeeCord; Pumpkin. Per PR, the affected plugins at their floor and the newest; the full matrix nightly and before a release.
- **Performance.** JMH for hot paths; a BotMark load and a Spark profile in the boot tests; the budgets above.
- **Security.** Build attestations (SLSA provenance) and checksums for every jar; fuzzing for the markup, config, vote receiver and xStats' site; CodeQL for Kotlin; store tokens in the `release` environment, which now needs an approval.
- **Releases.** One tag per plugin (`xbans-v2.0.0`); one workflow builds every jar and publishes:

| Store | Jars | How |
|---|---|---|
| GitHub Releases | all | automatic |
| Modrinth | every JVM jar, with its loaders and versions | automatic |
| Hangar | Paper, Velocity | automatic |
| voxel.shop | Bukkit family | automatic |
| CurseForge | Bukkit (dev.bukkit), Fabric, NeoForge, Hytale | automatic |
| SpigotMC | Bukkit family | no upload API: the listing downloads from GitHub, and the release writes the update post to paste |
| BuiltByBit | Bukkit family | its update API needs the paid Ultimate plan: a free listing that downloads from GitHub, unless we take Ultimate |
| Pumpkin | the Wasm build | its package manager or marketplace, once it's ready |

## Site

plugins.xenastudios.co stays Astro Starlight on Cloudflare. Generated from the code, and failing CI when stale: commands, permissions, config, messages, placeholders, events, and each plugin's platform and version table. Each plugin's page: overview, install tabs per platform, configuration, integrations, importers, FAQ, changelog. A download picker (platform and version, then the right jar and every store). For developers: Dokka at `/api/<plugin>/`, Gradle and Maven snippets at the latest version, Java and Kotlin guides, example add-ons. Today's docs and the rewrite's side by side until `maintenance` retires. A page of how complete each language is, and the support policy: which versions and platforms, for how long, and how fast security fixes ship. Search through Pagefind.

## Brand

Everything follows the [brand repo](https://github.com/xena-studios/brand): the plugin family look (a 16×16 glyph on a `raised` tile, the name's first letter in the plugin's accent, Space Grotesk and JetBrains Mono, the pixel-stepped horizon, XENA STUDIOS as the endorsement), and the catalog in `tokens/plugins-catalog.json`, which the README and site build from.

- **New plugins** get their brand first: `pnpm new-plugin xvanish Vanish Moderation` (and xMarket, xLimits) in the brand repo, `released: false`, glyph, headline, summary and blurb, before their milestone starts.
- **Words change with platforms.** Modrinth titles and summaries, taglines and blurbs say "For Paper and Folia" today; each major updates its catalog entry for the platforms it ships on, in the brand repo, before the release.
- **Store pages** (CurseForge, SpigotMC, BuiltByBit) use the same icon, banner and summary as Modrinth.
- **The site** uses the family's tokens (`tokens/plugins.css`), and XENA only in the footer, as the brand says.

## Milestones

Each one meets its exit before the next starts.

**M0: Spike and decisions.** Nothing released. KMP `common` for JVM and `wasmWasi`; a Pumpkin hello-world on `pumpkin-api-kt`, and Pumpkin's plugin-to-plugin calls and storage; a Java 17 jar with the fetched stdlib on Spigot 1.8.8 and 1.16.5; Fabric with `fabric-language-kotlin`; a YAML library and `kotlinx.serialization` on Wasm; Cloud and Adventure on 1.8.8, Fabric and Sponge; Stonecutter; a Java project calling a Kotlin API; CurseForge's upload API; site pages generated from code; the budgets' numbers. *Exit:* results and decisions in DECISIONS.md.

**M1: Foundations.** The KMP repo layout, build caches, the `new-plugin` scaffolder and `runServer` per platform. Core: config with migrations, i18n, markup, database, change feed, scheduler, event bus, API rules, library loader, importers, admin subcommands. `platform-bukkit` (Paper, Folia, Spigot, legacy) and `platform-fabric`. CI: the boot matrix, the family test, benchmarks, budgets, attestations. The release pipeline for every store. The new site's skeleton with generated pages. *Exit:* a sandbox plugin passes the full matrix and a dry-run release.

**M2: xUtilities 3.0, the pilot.** End to end on Paper, Folia, Spigot 1.8.8+ and Fabric. The core will change here; that's the point. Alpha on our own servers first. *Exit:* released everywhere, migration proven, page live.

**M3–M15: one plugin each**, in an order where what a plugin needs comes first:

| | Plugin | Why here |
|---|---|---|
| M3 | zChat 4.0 | the chat bridge per platform, and the proxy layer |
| M4 | xMoney 2.0 | the economy the others build on |
| M5 | xTeleport 2.0 | |
| M6 | xBans 2.0 | network-wide bans |
| M7 | xVanish 1.0 (#109) | the staff layer from xBans |
| M8 | xVote 2.0 | votes forwarded by the proxy |
| M9 | xMarket 1.0 (#110) | needs xMoney |
| M10 | xStats 2.0 | |
| M11 | xBackup 2.0 | |
| M12 | xRewind 2.0 | |
| M13 | xPlots 2.0 | |
| M14 | xLimits 1.0 (#111) | xRewind's block and entity hooks |
| M15 | xLimbo 3.0 | Paper only: core, translations, pipeline |

**M16: NeoForge and Sponge.** The two platforms, then each plugin as a minor.
**M17: Pumpkin.** Once its plugin ABI is stable: `platform-pumpkin`, then each plugin as a minor.
**M18: Hytale.** Experimental: `platform-hytale`, then the plugins that fit, on CurseForge.
**M19: Retire `maintenance`.** Once every major has been out about a month.

### What each plugin's milestone delivers

- [ ] Logic in `common`; adapters for the major's platforms.
- [ ] Boot tests pass at its floor and the newest version.
- [ ] Today's configs and databases migrate, and going back to the old jar works.
- [ ] Every message in `lang/en.yml`; launch languages reviewed.
- [ ] An API with events and add-on hooks; ABI dump committed.
- [ ] Integrations pass the family test; third-party hooks and importers work.
- [ ] Admin subcommands: `reload`, `debug`, and `import`, `migrate` and `forget` where they apply.
- [ ] Within every budget.
- [ ] Brand catalog entry updated for its platforms.
- [ ] Its page live, with the generated reference and platform table.
- [ ] Run as an alpha on our own servers.
- [ ] Released to every store.

## Risks

- **Scope.** Fourteen plugins on up to eight platforms. That's why a major needs four platforms and the rest come later in waves.
- **The pilot reshapes the core.** M3 doesn't start until M2 is released.
- **Pumpkin and Hytale are still moving.** They're late waves of their own, so neither can block a release.
- **Kotlin/Wasm limits `common`.** Enforced from the first commit.
- **Build time** grows with each plugin and platform. CI has a budget for it.
