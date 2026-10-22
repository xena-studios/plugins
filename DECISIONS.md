# Decisions

Why the plugins are built the way they are. Newest last.

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

Spike (`core/src/test/.../ConfigEmissionSpikeTest`): Paper's `YamlConfiguration` writes a commented config and reads it back with no server running. Each plugin declares its settings once (default, validation, comment); the same declaration writes the default `config.yml`, reads and checks the live one, and can generate the docs' "full default config" at build time, so the docs can't drift from the code.

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

xUtilities 2 is about 146 KiB against 1.x's 100.5: roughly 60 KiB of core (reload for everything, config migration, the theme, the update checker), 15 KiB of bStats, 50 KiB of commands, and 4 KiB of 1.x's default messages (so an upgrader who never changed one gets 2.0's look), plus zip overhead. Compiling without local variable tables (keeping line numbers, for stack traces) saved 13 KiB. Its budget is 150 KiB, so anything that grows it is a decision, not drift.

bStats lives in the core, relocated into each plugin's package; a plugin opts in by overriding `bStatsId()`. The IDs: xUtilities 34866, zChat 21533 (from zChat 1.0, which still reports to it, so zChat 3 carries its history on), xLimbo 34867. bStats' Bukkit class submits from its own thread on Folia, so it needs no scheduler of ours. Tests never start it (`xena.offline`). Runtime cost is what "lightweight" is really about: no repeating tasks, listeners only while their feature is on, and every message parsed once, not per send.

## Jar budgets are a tripwire, not a target (2026-10-22)

"Lightweight" means what a plugin costs while it runs: no repeating tasks, listeners only while their feature is on, messages parsed once. Jar size barely affects that, since a 160 KiB jar loads as fast as a 100 KiB one. Budgets that sat within 5 KiB of each jar failed on every reasonable feature, so each is now about 50 KiB above its jar (xUtilities 200, zChat 210, xLimbo 220) and a new plugin starts at 150 (the core and bStats alone are about 75). They still catch what they're for: a library shaded in by mistake, or code copied instead of shared, which add hundreds of KiB. A plugin within 10 KiB of its budget should get it raised on purpose, with the reason beside it.

## Renovate for dependencies, a workflow for Minecraft (2026-10-22)

Renovate, not Dependabot: it groups every minor and patch update into one weekly PR, merges it once CI passes, and shares one config across the org's repos (`xena-studios/.github`'s `renovate-config.json`). Automerge is per repo: here and in NeoSkript, where CI covers what a merge changes and nothing goes live; not in Raptor, the website (a merge deploys it) or brand (no CI). The minimum Paper is excluded, because raising it is a breaking change for owners.

New Minecraft versions aren't a dependency bump. They append to `xena.gameVersions` (Modrinth's list) and move two CI pins together, which Renovate can't express, and "released" means Paper has a stable build, not just a Maven artifact. `scripts/new-minecraft.sh` reads that from Paper's download API; `minecraft.yml` runs it daily (it costs nothing on a public repo, and a PR only opens when there's a new version) and opens the PR. A PR opened with the workflow's token doesn't trigger other workflows, so it starts `plugins.yml` itself.

## Messages share a theme (2026-10-21)

Every plugin's config starts with a `theme`: a prefix, a base colour, and `<highlight>`, `<success>` and `<error>` styles that messages use as MiniMessage tags. Default messages are written only in those, so an owner restyles a whole plugin in five lines, and every plugin in the family looks alike out of the box. The base colour wraps each chat message rather than being a tag, so messages don't need to open and close it. A message is parsed the first time it's sent and again only if the theme changes (on reload); messages with placeholders parse per send, as before.

When a default message changes, its old text goes in the spec with `formerDefault`. Carrying an old file forward gives any setting still on an old default the new one, in place, so owners who never touched their messages get the new look and owners who did keep theirs.

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

## MockBukkit for command logic, real servers for the rest (2026-10-21)

MockBukkit runs the commands' logic in milliseconds, but it can't `teleportAsync`, cancel Folia-style tasks, or tell harmful potion effects apart, and plugin classes it loads can't be `final`. Those paths are covered by `boot-checks.txt` on real servers, or by reading the code. CI also compiles against the newest Paper with removal warnings as errors (`-Pxena.newestPaper`), which is how `PotionEffectType#getCategory` (marked for removal in 26.3) was caught before release.
