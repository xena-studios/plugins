# Decisions

Why the plugins are built the way they are, oldest first.

| Decision | Date |
|---|---|
| [One minimum version: Paper 1.21.11, Java 21](#one-minimum-version-paper-12111-java-21-2026-10-21) | 2026-10-21 |
| [The rewrite ships as new majors, one plugin at a time](#the-rewrite-ships-as-new-majors-one-plugin-at-a-time-2026-10-21) | 2026-10-21 |
| [The core is shaded into each plugin, never installed on its own](#the-core-is-shaded-into-each-plugin-never-installed-on-its-own-2026-10-21) | 2026-10-21 |
| [Commands are registered at runtime, so command changes apply on reload](#commands-are-registered-at-runtime-so-command-changes-apply-on-reload-2026-10-21) | 2026-10-21 |
| [Configs are generated from a schema in code](#configs-are-generated-from-a-schema-in-code-2026-10-21) | 2026-10-21 |
| [Boot tests on real servers](#boot-tests-on-real-servers-2026-10-21) | 2026-10-21 |
| [The core: what every plugin gets](#the-core-what-every-plugin-gets-2026-10-21) | 2026-10-21 |
| [Jar budgets are per plugin, and honest](#jar-budgets-are-per-plugin-and-honest-2026-10-21) | 2026-10-21 |
| [Messages share a theme](#messages-share-a-theme-2026-10-21) | 2026-10-21 |
| [MockBukkit for command logic, real servers for the rest](#mockbukkit-for-command-logic-real-servers-for-the-rest-2026-10-21) | 2026-10-21 |
| [Jar budgets are a tripwire, not a target](#jar-budgets-are-a-tripwire-not-a-target-2026-10-22) | 2026-10-22 |
| [Renovate for dependencies, a workflow for Minecraft](#renovate-for-dependencies-a-workflow-for-minecraft-2026-10-22) | 2026-10-22 |
| [zChat's chat path](#zchats-chat-path-2026-10-22) | 2026-10-22 |
| [xLimbo is Paper only](#xlimbo-is-paper-only-2026-10-22) | 2026-10-22 |
| [Main is protected, and checked by CodeQL](#main-is-protected-and-checked-by-codeql-2026-10-23) | 2026-10-23 |
| [A config that can't be saved is still used](#a-config-that-cant-be-saved-is-still-used-2026-11-17) | 2026-11-17 |
| [In-game edits write config.yml](#in-game-edits-write-configyml-2026-11-17) | 2026-11-17 |
| [Releases: the job that runs Gradle can't write to the repo](#releases-the-job-that-runs-gradle-cant-write-to-the-repo-2026-11-17) | 2026-11-17 |
| [Audit follow-ups: split words, vanish, reloads](#audit-follow-ups-split-words-vanish-reloads-2026-11-17) | 2026-11-17 |
| [A shared database layer, with no drivers shaded in](#a-shared-database-layer-with-no-drivers-shaded-in-2026-11-17) | 2026-11-17 |
| [xRewind's write path: references in, numbers out](#xrewinds-write-path-references-in-numbers-out-2026-11-17) | 2026-11-17 |
| [xPlots: plots in memory, found by arithmetic](#xplots-plots-in-memory-found-by-arithmetic-2026-11-17) | 2026-11-17 |
| [Developer APIs ship inside the plugin, and shared code lives in the core](#developer-apis-ship-inside-the-plugin-and-shared-code-lives-in-the-core-2026-11-17) | 2026-11-17 |
| [Network mode: one change feed in the database](#network-mode-one-change-feed-in-the-database-2026-11-17) | 2026-11-17 |
| [Chat pages, offline players, warmups and Vault move to the core](#chat-pages-offline-players-warmups-and-vault-move-to-the-core-2026-11-17) | 2026-11-17 |
| [xVote: a receiver of our own, and votes taken before they're paid](#xvote-a-receiver-of-our-own-and-votes-taken-before-theyre-paid-2026-11-18) | 2026-11-18 |

## One minimum version: Paper 1.21.11, Java 21 (2026-10-21)

Every plugin supports the same oldest Paper, so the core is written once against one API. 1.21.11 is the last 1.21 release and still widely run; 26.x needs Java 25, so building for 1.21.11 with Java 21 bytecode covers both. xLimbo drops 1.18.2. Raising the minimum is a breaking change and needs a new major version.

## The rewrite ships as new majors, one plugin at a time (2026-10-21)

xUtilities 2, then zChat 3, then xLimbo 2, each released before the next starts, so the docs always describe released versions. Each carries old configs forward automatically, backing up the old file first. The old source is gone; the docs are the spec.

## The core is shaded into each plugin, never installed on its own (2026-10-21)

Server owners install one jar per plugin and nothing else. Each plugin relocates its copy of the core (`co.xenastudios.<plugin>.core`), so plugins built on different core versions never clash, and `minimize()` drops the core classes a plugin doesn't use.

## Commands are registered at runtime, so command changes apply on reload (2026-10-21)

Spike (`sandbox/`, checked in CI): a command registered through the Bukkit command map after startup runs by name, alias and `namespace:name`, tab-completes, and appears in the command tree the server sends clients. Removing it from `getKnownCommands()` takes it out of that tree, and re-registering with new aliases drops the old ones. Each takes well under 10 ms. This passes on Paper and Folia, 1.21.11 and 26.3.

So turning a command on or off, its aliases and permission, and adding or removing link commands can all apply on `/<plugin> reload` (followed by `Player#updateCommands`), instead of needing a restart. A disabled command isn't registered at all, so another plugin can take its name. Paper's Brigadier lifecycle API can't do this: it only registers at startup and on a datapack reload.

Still to confirm with a real client: that `updateCommands` refreshes tab completion for players already online. (It's the call LuckPerms and others make for the same reason.)

Gotcha: a command dispatched while another command is running is queued until that one finishes, so code that dispatches commands must run outside a command's execution.

## Configs are generated from a schema in code (2026-10-21)

A spike showed that Paper's `YamlConfiguration` writes a commented config and reads it back with no server running. (That test is gone; the core's `ConfigLoaderTest` now covers writing and reading configs.) Each plugin declares its settings once (default, validation, comment); the same declaration writes the default `config.yml`, reads and checks the live one, and generates the docs' "full default config" in each plugin's `DefaultConfigTest`, so the docs can't drift from the code.

## Boot tests on real servers (2026-10-21)

`scripts/boot-test.sh` boots a real Paper or Folia server with the plugin jars, runs console commands, stops it, and fails on any error in the log or any warning from our plugins. CI runs it at the minimum version and the newest, on both. Unit tests can't catch a plugin that fails to enable, or a Folia threading mistake; this does.

## The core: what every plugin gets (2026-10-21)

Built with xUtilities 2. A plugin extends `XenaPlugin`, declares its config as records (`ConfigSpec`), and says in `setup(config)` which commands, permissions and modules that config wants. The core applies the difference on startup and on every reload, so a plugin never handles reload itself. It also gives every plugin `/<plugin> reload|info` and the update checker, and isolates failures: a command that throws sends "something went wrong" and logs the trace; a module that won't start stays off; neither takes the rest down.

- **Permissions are declared from the config**, with the command's default, so a renamed permission keeps it (a renamed `/ping` permission is still everyone's).
- **Server commands are replaced and given back.** Paper wraps vanilla commands in a new object on every lookup, so they're recognised as "not a plugin's" rather than by identity. Another plugin's command is never replaced: that clash is a console warning, and ours stays reachable namespaced.
- **The default config is written by the core's own emitter**, not Bukkit's: quoted strings, inline lists, comments, blank lines between sections. Carrying an old file forward still goes through Bukkit, which keeps the owner's values and comments.
- **A file that isn't valid YAML is never overwritten.** On reload the running config stays; at startup the defaults run until it's fixed.
- **Tests never reach the network**: they run with `-Dxena.offline=true`, which keeps the update checker and bStats off.

## Jar budgets are per plugin, and honest (2026-10-21)

xUtilities 2 is about 146 KiB against 1.x's 100.5: roughly 60 KiB of core (reload for everything, config migration, the theme, the update checker), 15 KiB of bStats, 50 KiB of commands, and 4 KiB of 1.x's default messages (so an upgrader who never changed one gets 2.0's look), plus zip overhead. Compiling without local variable tables (keeping line numbers, for stack traces) saved 13 KiB. Its budget is 150 KiB, so anything that grows it is a decision, not drift. (Budgets were raised the next day: see [Jar budgets are a tripwire, not a target](#jar-budgets-are-a-tripwire-not-a-target-2026-10-22).)

bStats lives in the core, relocated into each plugin's package; a plugin opts in by overriding `bStatsId()`. The IDs: xUtilities 34866, zChat 21533 (from zChat 1.0, which still reports to it, so zChat 3 carries its history on), xLimbo 34867, xRewind 35410, xPlots 35419. bStats' Bukkit class submits from its own thread on Folia, so it needs no scheduler of ours. Tests never start it (`xena.offline`). Runtime cost is what "lightweight" is really about: no repeating tasks, listeners only while their feature is on, and every message parsed once, not per send.

## Messages share a theme (2026-10-21)

Every plugin's config starts with a `theme`: a prefix, a base colour, and `<highlight>`, `<success>` and `<error>` styles that messages use as MiniMessage tags. Default messages are written only in those, so an owner restyles a whole plugin in five lines, and every plugin in the family looks alike out of the box. The base colour wraps each chat message rather than being a tag, so messages don't need to open and close it. A message is parsed the first time it's sent and again only if the theme changes (on reload); messages with placeholders parse per send, as before.

When a default message changes, its old text goes in the spec with `formerDefault`. Carrying an old file forward gives any setting still on an old default the new one, in place, so owners who never touched their messages get the new look and owners who did keep theirs.

## MockBukkit for command logic, real servers for the rest (2026-10-21)

MockBukkit runs the commands' logic in milliseconds, but it can't `teleportAsync`, cancel Folia-style tasks, or tell harmful potion effects apart, and plugin classes it loads can't be `final`. Those paths are covered by `boot-checks.txt` on real servers, or by reading the code. CI also compiles against the newest Paper with removal warnings as errors (`-Pxena.newestPaper`), which is how `PotionEffectType#getCategory` (marked for removal in 26.3) was caught before release.

## Jar budgets are a tripwire, not a target (2026-10-22)

"Lightweight" means what a plugin costs while it runs: no repeating tasks, listeners only while their feature is on, messages parsed once. Jar size barely affects that, since a 160 KiB jar loads as fast as a 100 KiB one. Budgets that sat within 5 KiB of each jar failed on every reasonable feature, so each is now about 50 KiB above its jar (xUtilities 200, zChat 210, xLimbo 220) and a new plugin starts at 150 (the core and bStats alone are about 75). They still catch what they're for: a library shaded in by mistake, or code copied instead of shared, which add hundreds of KiB. A plugin within 10 KiB of its budget should get it raised on purpose, with the reason beside it.

## Renovate for dependencies, a workflow for Minecraft (2026-10-22)

Renovate, not Dependabot: it groups every minor and patch update into one weekly PR, merges it once CI passes, and shares one config across the org's repos (`xena-studios/.github`'s `renovate-config.json`). Automerge is per repo: here and in NeoSkript, where CI covers what a merge changes and nothing goes live; not in Raptor, the website (a merge deploys it) or brand (no CI). The minimum Paper is excluded, because raising it is a breaking change for owners.

New Minecraft versions aren't a dependency bump. They append to `xena.gameVersions` (Modrinth's list) and move two CI pins together, which Renovate can't express, and "released" means Paper has a stable build, not just a Maven artifact. `scripts/new-minecraft.sh` reads that from Paper's download API; `minecraft.yml` runs it on Mondays, with Renovate's batch, and opens the PR; it can also be run by hand when a version comes out. A PR opened with the workflow's token doesn't trigger other workflows, so it starts `plugins.yml` itself.

## zChat's chat path (2026-10-22)

Every chat message runs through one `AsyncChatEvent` handler, cheapest check first: mute, cooldown, filter, anti-repeat, then mentions and the format. The format is set as a viewer-unaware renderer, so Paper draws the line once and sends it to every viewer. Measured on MockBukkit, a whole message costs about 40 µs off the main thread; the filter is about 2.5 µs and drawing the format about 10 µs.

- **State is safe from any thread.** Chat runs on many threads at once (Folia especially), so the mute, cooldowns, hidden chat, spies and reply targets live in concurrent maps. Mentions look names up in zChat's own concurrent index, kept on join and quit, not the server's player map, which isn't meant for that.
- **The smart filter folds, then matches whole tokens.** Each character is lowercased, stripped of accents, and has number-for-letter swaps undone; everything else is a gap. Runs of single letters ("b a d") are joined. A word becomes a regex where each run of a letter matches that many or more ("baad" → `b+a{2,}d+`), so stretched letters are caught but "as" never matches "ass". Matching whole tokens is what keeps *class* clean. The original index of every folded character is kept, so censoring hits exactly what was typed.
- **PlaceholderAPI values are inserted, not pasted.** `%name%` in a format is rewritten to `<papi:'name'>` at load; the tag inserts the value as legacy-coloured text after parsing. Pasting it into the template first, as zChat 2 did, let any placeholder that shows player-chosen text inject clicks and hovers.
- **Group permissions default to false.** An operator getting the admin format because they're an operator surprised owners.
- **A zChat 1.x config is set aside, not migrated.** It was a different plugin with no `config-version`; `ConfigSpec.foreign` recognises it, moves it to `config.yml.old` and writes the defaults.

## xLimbo is Paper only (2026-10-22)

Folia can't create worlds at runtime, and creating its void world is xLimbo's core job. A limbo also has nothing for Folia to parallelise: no mobs, no redstone, players standing still. Its real load is a mass arrival (logins and chunk sending), which Folia barely helps. So `folia = false`, and xLimbo is free to use Paper-only API.

- **Players are placed before they're in the world.** `AsyncPlayerSpawnLocationEvent` sets the spawn while the player is still configuring, so there's no teleport after joining and each arrival loads one spot once.
- **The spawn area is kept loaded** with plugin chunk tickets (at most 17×17 chunks), and the default spread is 64 blocks, not 1.x's 4000: a mass arrival is a few dozen ready chunks, not a fresh chunk per player.
- **The return queue is main-thread state** fed by an async status ping (a raw Server List Ping, about 60 lines, no library). A server only answers status once it's ticking, so an answer means it's ready for players. Players are tried at most three times in five minutes, so a ban on the main server can't bounce someone forever.
- **No game rules.** Minecraft 1.21.11 renamed them and Paper marked the old constants for removal; every rule xLimbo wanted already had a better route (event cancels, the void biome's empty spawn list, a long clear-weather spell).
- **xLimbo only deletes what it made.** A world folder xLimbo creates gets an `xlimbo.dat` marker with the floor it was made with; only marked folders are ever deleted, rebuilt or unloaded. A world it didn't make (the main world, a Multiverse world) is configured and nothing more, and an unloaded folder it didn't make is refused rather than adopted or deleted. Folders are found through the server, since 26.1 keeps extra worlds under `<main world>/dimensions/minecraft/`.
- **Players can't see each other by default** (`player.hide-others`). Hundreds of players arriving in one spot each tracking every other one is a limbo's biggest load by far, much more than anything xLimbo itself does.
- **The jar is about 165 KiB**: the shared core and bStats are about 75 KiB, and the world safety, return queue, status ping and protections the rest.
- **Rebuilding on reload** moves players to the server's main world, unloads the limbo, deletes it if it's temporary, makes it again and puts them back. The main world itself can't be rebuilt, so in single-world mode floor changes say "restart".

## Main is protected, and checked by CodeQL (2026-10-23)

`main` takes changes only through pull requests, never force-pushed or deleted, and requires two checks: `plugins-ok` and `docs-ok`. A required check has to report on every PR, but each workflow only has work for some of them, so each runs on every PR, skips what the change doesn't touch (its `changes` job), and ends in one job that passes when everything else passed or was skipped. A PR that the New Minecraft workflow opens has to start both workflows itself.

Code scanning is CodeQL, GitHub's own: the deepest free dataflow analysis for Java (a player's chat line followed into a command, for example), with results on the PR. Java is traced through a real Gradle build rather than CodeQL's build-less mode, for accuracy; the workflows and the docs' scripts are scanned too. `security-extended` queries, weekly as well as on every change. Dependencies are left to Renovate, which opens a fix PR as soon as one has a known vulnerability (`osvVulnerabilityAlerts`, in the org's shared settings): Dependabot alerts and its fix PRs would duplicate it.

## A config that can't be saved is still used (2026-11-17)

Loading writes the file back whenever it changes it (carrying it forward, adding new settings). Some hosts mount plugin folders read-only, and some volumes can't replace a file in one step. Before, either case dropped the owner's whole config for the defaults. Now a failed write is a warning and the settings are used as read; a mount without atomic moves falls back to a plain replace; and a migration whose backup can't be made is carried forward in memory only and never written, so a backup can't pile up on every start. `ConfigLoader.read` peeks at the settings without writing anything, for code that runs before the plugin's own load (xLimbo's world generator).

## In-game edits write config.yml (2026-11-17)

`/filter` changes `filter.words` in `config.yml` rather than keeping its own file, so there's one list whether staff edit it in game or by hand, and the docs' config page stays the whole story. The edit goes through `XenaPlugin.editConfig`: on the global thread outside the command, Bukkit reads the file (keeping the owner's comments), the change is checked against the file itself (so a hand edit made without a reload counts), the file is swapped in whole, and the normal reload applies it. A file that isn't valid YAML is never touched, and a change that changes nothing writes nothing. Bukkit writes lists in block style, as the config migration already does. Any plugin can use it for in-game settings later.

## Releases: the job that runs Gradle can't write to the repo (2026-11-17)

`release.yml` is two jobs. **publish** checks the tag, builds and publishes to Modrinth with the `release` environment's token, but its `GITHUB_TOKEN` can only read. **github** has `contents: write` and runs no project code (no checkout, no Gradle): it uploads the jar and notes the first job built. So a build script, a Gradle plugin or a dependency can never push to the repo or rewrite a release. Workflows default to `permissions: {}` and each job asks for what it needs.

## Audit follow-ups: split words, vanish, reloads (2026-11-17)

- **The smart filter joins short pieces only into whole words.** Runs of one- and two-letter pieces ("ba dw or d") are joined and must make a whole filter word, never contain one, so "is it to an" can't censor anything. Words with a `*` are left out, because "as so" makes `*ass*`. Pieces longer than two letters aren't joined: "pen is" would make a word.
- **`Player#canSee` from the chat thread is accepted as racy.** Paper keeps who a player has hidden in a plain `HashMap` written on the main thread. Read from the chat thread it can give the wrong answer around the moment someone vanishes, but not crash, so mentions still use it for the highlight. The ping asks again on the target's own thread (the main thread on Paper), so a vanished player is never pinged. A concurrent snapshot would need events Paper doesn't fire for hide and show.
- **xLimbo's return history lives on the plugin.** The return queue restarts when server-check's settings change on reload; the tries and arrival times it reads are kept by the plugin, so a reload can't hand a refused player three more tries. Tries outlive a quit: being sent back is quitting.
- **Plugins can let go of things on disable.** `XenaPlugin.shutdown()` runs after the modules stop. xLimbo uses it to unload a world it made when it's disabled while the server keeps running (`/reload confirm`), so the world doesn't keep the old instance's generator; the new instance makes it again and brings players in. A world the server loaded itself can't be remade, so its floor is read from a generator of the same class name.

## A shared database layer, with no drivers shaded in (2026-11-17)

xRewind keeps its history in a database, and the voting and economy plugins will too, so `core/db` opens one from a shared `storage` config section: SQLite in the plugin's folder by default, MySQL or MariaDB for networks.

- **No driver in the jar.** Paper and Folia ship sqlite-jdbc and mysql-connector-j (checked on 1.21.11 and 26.3), and a paper-plugin's class loader sees them. Shading them would add about 14 MiB. If Paper ever drops them, a `PluginLoader` can fetch them at startup.
- **SQLite is set up for one writer and many readers:** WAL, so lookups never wait for the writer; `synchronous=NORMAL`, safe with WAL (a power cut can lose the last moments, never corrupt the file); `auto_vacuum=INCREMENTAL`, so purges give space back a little at a time instead of locking the file for a full `VACUUM`.
- **Numbered schema steps**, recorded in a `meta` table, each in its own transaction. A database made by a newer version is refused, never changed.
- **Connections on demand, no pool.** A plugin that writes a lot keeps one connection for its writer; reads are staff commands, rare enough to open their own.

xRewind opens its database off the server's threads, so a slow or unreachable MySQL never holds up startup or a reload, and a reload that can't open a new database keeps the old one. Its rows are only numbers: names, worlds, block states and items are stored once in dictionary tables, and times are seconds since 2026 in four bytes, which last until 2094.

## xRewind's write path: references in, numbers out (2026-11-17)

A listener does as little as it can: it builds one record from what the event already has (the player's uuid and name, the world's name, the block's `BlockData` before and after) and adds it to a lock-free queue. It looks nothing up and takes no lock, so it's as cheap on Folia's region threads as on Paper's main thread: about 40 ns in the benchmark (`WriterTest`), against a budget of 2 µs.

One writer thread does the rest. It owns the dictionaries that turn names, worlds and block states into ids, as plain maps, since nothing else touches them, and writes batches of up to 10,000 rows, each one transaction, every half second or as soon as 2,000 are waiting. New dictionary rows go in the same transaction as the first rows that use them, so no row ever points at a name that isn't there. A batch that fails is kept and retried on a fresh connection, after a pause that grows to 30 seconds; the console hears about it once, not on every retry. Until the database is ready, or while it's down, entries wait in the queue: the console is warned at 100,000, and past a million new ones are counted and dropped rather than run the server out of memory. Stopping writes what's left, for up to 10 seconds.

A batch that keeps failing on a live connection (three times in a row) is a row the database won't take, not the database being down, so its rows are then written one at a time and only that one is dropped, with a warning; `/xrewind status` counts it as missed. MySQL tables are made utf8mb4 whatever the database's default, so a heart on a sign or an emoji in chat is never that row.

`BlockData` is safe to read from the writer: the server's block states are immutable, and `getBlockData` hands back a copy.

Plugins can now add subcommands to their own command (`Setup.subcommand`): `/xrewind status` sits beside `reload` and `info`, each with its own permission, so staff who aren't admins can use the ones they're given.

## xPlots: plots in memory, found by arithmetic (2026-11-17)

xPlots replaces a Skript that rented one WorldGuard region per plot and rewrote every nearby sign each second. It's Paper only for 1.0, like xLimbo: putting plots back and editing signs across Folia's regions adds risk with little gain for box servers.

- **No region per plot.** A field is one selection split into a grid, and the plot for a block is worked out from the grid: `(x - origin) / (size + path)`, the remainder saying "path". Each world keeps the box around all its fields, so a block outside every field costs one check. A field's whole footprint is protected at every height; plots are buildable only between the selection's lowest and highest blocks. With WorldGuard installed, xPlots makes one region per field that allows building, so a world-wide `build deny` doesn't block plots, and decides who may build itself.
- **Everything in memory, loaded before anyone joins.** Plots are read once, synchronously, while the server starts, so there's never a moment when plots exist but aren't protected. So `storage` applies on restart, unlike xRewind's. Changes are copied on the main thread and written by one thread in order, retried while the database is down, so the database never has a later change without an earlier one.
- **One timer.** It's set for whatever is due next (a lease running out, or its owner's warning a day or an hour before) and never waits more than 20 minutes, so lag or a changed clock can't put it far off. A warning due while the owner is offline is sent when they join. Signs are checked once a minute, only in loaded chunks, and rewritten only when what they show changes.
- **Snapshots, not schematics.** Each plot's blocks are saved when it's made, as a palette of block states and an index per block, deflated: a few hundred bytes for a small plot. Saving and putting back run a few milliseconds per tick, after loading chunks in the background. A plot being put back can't be built in or rented until it's done, and one still going at shutdown is finished rather than left half done.
- **Vault without a hard dependency.** The economy is looked up when it's used, since economy plugins often register after xPlots starts. Item currency matches material, and name and custom model data when set.
- **WorldGuard is compiled against 7.0.14**, the newest built for Java 21, with only its API jars: their own dependencies pin a Guava that clashes with newer Paper's.

## Developer APIs ship inside the plugin, and shared code lives in the core (2026-11-17)

Every plugin gets a public API (issue #42), in `<plugin>/api` on the `xena.api` convention: interfaces, records, enums and events, depending on nothing but paper-api. The plugin shades it into its own jar unrelocated and never minimized, so other plugins find the API classes in the plugin they depend on, and server owners still install one jar. It's published as `co.xenastudios:<plugin>-api` (Maven Central with the first released API). Because an API module can't see the core, an API can't leak core types, which are relocated per plugin and would differ between plugins anyway.

Checked on real servers (`sandbox/api`, used by `sandbox/consumer` and `sandbox/paper-consumer` in CI): on Paper and Folia, 1.21.11 and 26.3, an ordinary `plugin.yml` plugin with `depend` and a Paper plugin with `join-classpath: true` both load the API classes from a paper-plugin and reach it through Bukkit's services manager.

Binary-compatibility checks (japicmp) start with the first released API, since until then there's nothing to compare against.

Code two plugins need moves to the core instead of being copied (issue #48). The root build runs PMD's copy-paste detector over every module and fails when a block of about five lines or more is in two modules; repeats inside one module are only reported. The first move: the `Toggle` settings every switchable command has, which xUtilities and zChat each defined and xLimbo wrote out by hand, is now `co.xenastudios.core.command.Toggle`, with `CommandSpec.named(name, settings)`.

## Network mode: one change feed in the database (2026-11-17)

xBans, xVote, xMoney and xStats keep what's hot (active bans, balances) in memory, which is only right on one server. On a network sharing one MySQL or MariaDB database, a ban made on one server has to reach the others' memory. `core/db`'s `ChangeFeed` does that for every plugin, instead of each writing its own.

- **A table, not a message broker.** The servers already share the database, so a change is a row in the plugin's `changes` table (topic, a small payload such as a uuid, the server it came from, when). Redis or a proxy channel would be one more thing to run, or would need a player online to carry the message. A plugin publishes in the same transaction as the change itself, so the others never hear of a change that rolled back.
- **Off unless asked for.** A plugin with network support adds `NetworkSettings` as its `network` section, off by default. Off, or on SQLite (one server by definition, and the console is told), publishing does nothing, no table is made and nothing repeats. On, one task off the server's threads checks every `check-every` seconds (3 by default), so a change reaches the others in about that.
- **One index range per check.** Each check reads rows above the newest id it has seen, by the table's key, at most 500 at a time; its own server's rows (a random id per start) are skipped, and subscribers get the rest in order on the feed's thread, hopping to the right thread themselves.
- **Late commits aren't lost.** Ids are handed out on insert, not on commit, so a slow transaction can commit after a newer row was already read. An id skipped over is asked for again on each check for 15 seconds (a rolled-back insert leaves a gap forever, so it isn't waited on longer), and a jump of over 1,000 ids isn't waited on at all.
- **Changes from before a start aren't replayed.** A starting server reads its tables anyway; it starts from the newest row.
- **Pruned to ten minutes, never empty.** Once a minute each server deletes rows older than ten minutes below the newest it has seen, by key range. The newest row always stays, so an old MySQL that forgets its counter on restart can't hand out an id again.
- **A server that may have missed changes reads again.** A feed is only as good as the server's memory of what it read, so `onResync` tells a plugin to reload what it keeps in memory from its tables whenever that can't be trusted: when checks start working after failing, when none worked for five minutes (rows it never saw may be pruned), and after a jump in ids too big to wait for. Found reviewing xBans (#63): without it, a server cut off from MySQL for more than ten minutes kept enforcing lifted bans and missed new ones until a restart.

It's tested on SQLite with two feeds sharing a file (`ChangeFeedTest`, including late and never-committed rows and pruning), and against a real MariaDB when `XR_MYSQL` is set (`ChangeFeedMySqlTest`, as xRewind's), which CI runs.

## Chat pages, offline players, warmups and Vault move to the core (2026-11-17)

Each of these has at least two users among the plugins being built (#48's table), so each is in the core before the second one is written.

- **`text/ChatPages`**: a list a page at a time, from memory or a page already read from a database with its total, and a footer players click. The footer's `<next>` and `<previous>` are filled in as pre-processed tags, because inside a `<click>` an ordinary placeholder isn't filled in at all: xRewind's "Next page" ran `<next>` until it moved onto this. `<has_next>` and `<has_previous>` hide a link when there's no such page. `Tasks.sender` sends a reply on the player's thread, or the global one for the console, for lists read off the server's threads.
- **`player/Profiles`**: a name to a uuid and back for players who may be offline, never blocking. What's in memory (online players, its own 2,000 answers, the server's user cache) answers at once with a completed future; the player's saved data and Paper's profile lookup run off the server's threads. Two lookups of one player share one, and a name Mojang doesn't know isn't asked about for ten minutes, so typos can't use up its rate limit.
- **`player/Warmups`**: from xPlots. A short task on the entity's own scheduler checks four times a second for moving half a block, so it's Folia-safe though xPlots isn't, and costs nothing for anyone not waiting. Damage cancelling needs a listener, so one is registered only while a warmup that damage cancels is running. The countdown can show on the action bar. It works on any entity, which is how the sandbox checks it on real Paper and Folia servers with a pig.
- **`hook/VaultEconomy`**: from xPlots. The class plugins use never names a Vault type, and the one that does is only loaded once Vault is enabled, so a server without Vault never sees a `NoClassDefFoundError` (the sandbox checks this on servers without Vault). The economy is looked up each time, as before.

xPlots keeps its own `Wallet` (an economy or an item) and its teleport messages and bypass permission; only the general parts moved. Its jar grew about 8 KiB, mostly the warmup's damage and countdown options that xTeleport will use.

## xVote: a receiver of our own, and votes taken before they're paid (2026-11-18)

xVote receives votes itself instead of depending on NuVotifier, which hasn't had a release since 2021 and which nearly every voting plugin needs beside it.

- **NuVotifier's protocols, byte for byte, without Netty.** The greeting, v1's 256-byte RSA block (PKCS#1 v1.5, a 2048-bit key) and v2's signed JSON (HMAC-SHA256 of the payload with the list's token, the greeting's challenge inside, 0x733A and a length in front), and the same error replies. Keys stay in `rsa/` as NuVotifier writes them and tokens in a `tokens.yml` with its `tokens:` section, so a NuVotifier folder is taken over on the first start and lists never change. Tokens are kept out of `config.yml`, which owners paste when asking for help. They're read with a path separator no name has, because Bukkit splits keys on dots and `MinecraftServers.org` would become two sections (NuVotifier itself trips on this). The tests use a key, a v1 block and an HMAC made with openssl, plus votes built exactly as NuVotifier's own client builds them, so the same mistake can't sit on both sides.
- **One platform thread accepts, each connection gets a virtual thread,** under a hard cap (more are closed at once), with a deadline for the whole conversation rather than per read, so a byte at a time can't hold a slot, and a limit per address per minute checked before anything is read or decrypted. Each kind of error is logged once per source until a reload. JSON is Paper's Gson; nothing is shaded in.
- **A vote is taken before it's paid.** Every vote is a row; paying one starts with `UPDATE ... SET claim = ? WHERE claim IS NULL` in the database, so two servers sharing it can't both pay a vote, however they race (tested with two servers on one SQLite file, and on MariaDB with the real change feed). A crash between taking and paying loses that reward rather than paying it twice; a player who leaves before the reward reaches their thread gives the vote back. The vote's place in the player's count, streak and first-of-the-day are stored when it's matched to a player, so whichever server pays it pays the same.
- **Names wait.** Lists send names, not uuids. A name is matched from memory only (online, voted before, or the server's user cache), never by asking Mojang, because a name that never joined may be a typo or a Bedrock player; it waits, and the first player with that name to join gets it. On a network, the change feed's news of a vote lets the server where that name is online match it.
- **Totals keep their period's key** (the day, week and month they were counted in), so nothing resets them at midnight and no task runs. Leaderboards are sorted again only on the first question after a vote.
- **The receiver starts off the server's threads,** since it reads key files and may make a key, one change at a time, and never binds after the plugin is disabled.
- **The jar is about 310 KiB:** the receiver, the ledger and its network claims, rewards, commands and the API. Its budget is 350.

