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

xUtilities 2 is about 132 KiB against 1.x's 100.5: roughly 55 KiB of core (reload for everything, config migration, the update checker), 15 KiB of bStats and 50 KiB of commands, plus zip overhead. Compiling without local variable tables (keeping line numbers, for stack traces) saved 13 KiB. Its budget is 140 KiB, so anything that grows it is a decision, not drift.

bStats lives in the core, relocated into each plugin's package; a plugin opts in by overriding `bStatsId()`. The IDs: xUtilities 34866, zChat 21533 (registered for zChat 2, so its history carries on), xLimbo 34867. bStats' Bukkit class submits from its own thread on Folia, so it needs no scheduler of ours. Tests never start it (`xena.offline`). Runtime cost is what "lightweight" is really about: no repeating tasks, listeners only while their feature is on, and every parse done at load.

## MockBukkit for command logic, real servers for the rest (2026-10-21)

MockBukkit runs the commands' logic in milliseconds, but it can't `teleportAsync`, cancel Folia-style tasks, or tell harmful potion effects apart, and plugin classes it loads can't be `final`. Those paths are covered by `boot-checks.txt` on real servers, or by reading the code. CI also compiles against the newest Paper with removal warnings as errors (`-Pxena.newestPaper`), which is how `PotionEffectType#getCategory` (marked for removal in 26.3) was caught before release.
