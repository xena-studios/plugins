# xUtilities changelog

## 3.0.0 (unreleased)

Not released yet: 3.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 2.1 is the release, and 2.x gets its fixes.

The rewrite: the same commands, permissions and settings as 2.1, now on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, **Fabric 1.20.1, 1.21.11 and 26.3**, **NeoForge 1.21.1, 1.21.11 and 26.3**, **Forge 1.20.1** and **Sponge from API 8 (1.16.5) to API 21 (26.3)**, one jar (server-side: players install nothing), in eight languages. Keep the 2.1.0 jar to go back to.

### Upgrading from 2.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v2` (`config.yml.bak-v1` from 1.x). See [Upgrading from 2.x](https://plugins.xenastudios.co/xutilities/upgrade/).

- **Messages are in `lang/` now**, one file per language (`lang/en.yml`, `lang/de.yml`, ...), no longer in `config.yml`. Every message you changed in 2.x is copied into `lang/en.yml` on the first start, and then xUtilities sends English to everyone (`messages.language: en`), as 2.x did. Set it to `auto` to give each player their own language. Messages you never changed get 3.0's.
- **`theme` moved into `messages.theme`**, and is shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here. Colours you changed in 2.x stay this plugin's own.
- The error messages (`messages.error.*`) are the core's now: `core.command.*` in `lang/`. A changed one is carried there.
- `update-checker` is `updates` now, the core's: `update-checker.enabled` is carried to `updates.check`, and `updates.channel` picks which releases you hear of (`release`, `beta` or `alpha`). `update-checker.message` is `core.update.available` in `lang/`, and the notice goes to players with `xutilities.admin.update` as they join.
- Every command, alias, permission, link command and `/back` works as it did. Nothing else to change.
- **Going back to 2.1.0:** stop the server, put the 2.1.0 jar back, and rename `config.yml.bak-v2` to `config.yml` (keep 3.0's as `config.yml.v3` to come back to). If you don't, 2.1.0 still starts: it reads 3.0's file without changing it, keeps your commands' settings and link commands, and uses its own default messages and theme.

### For developers: the API's breaking changes

- `XUtilitiesApi.get()` is `XUtilities.api()` (any platform), and the API is still in Bukkit's services manager.
- Players are `PlayerRef` (by UUID: `PlayerRefs.of(player.getUniqueId())`), and places are the core's `Location`, not Bukkit's `Player` and `Location`, so the same API works on Fabric, NeoForge and Forge. `getBackLocation` returns the location or `null` instead of an `Optional`.
- The events are on `XUtilities.api().getEvents()` (`BackRecordEvent.TYPE`, `BackTeleportEvent.TYPE`). On Bukkit they're also Bukkit events, now named `BukkitBackRecordEvent` and `BukkitBackTeleportEvent` in `co.xenastudios.xutilities.api.bukkit`, with Bukkit's `Player` and `Location` as before.
- See [Developer API](https://plugins.xenastudios.co/xutilities/developer-api/).

### New

- **Sponge**, API 8 (SpongeVanilla 1.16.5) on, one jar for every API line. Everything but the workstation menus, which SpongeAPI can't open without their block; `/back` remembers deaths and xUtilities' own teleports, as on Fabric.
- **Spigot, CraftBukkit and Bukkit 1.8.8 to 26.3**, and **Fabric**. Where a server can't do something, the command says so instead of failing: `/ping` needs 1.17 on Spigot (Paper has it earlier), and the workstation menus other than `/craft` need Paper 1.14 or Spigot 1.21.4. On Fabric, `/back` remembers deaths and xUtilities' own teleports (Fabric has no teleport event for other mods' without a mixin).
- **NeoForge 1.21.1, 1.21.11 and 26.3**, and **Forge 1.20.1**, a jar per line, with Kotlin for Forge. Every command and menu works as on Fabric, and `/back` remembers what it does there. On Forge 1.20.1 each player gets their own language, which Fabric 1.20.1 can't give.
- **Eight languages**, each player's own: English, German, Spanish, French, Brazilian Portuguese, Russian, Polish and Simplified Chinese. The seven besides English are machine-drafted, waiting for native speakers to read them; corrections are welcome.
- `/xutilities debug`: checks that the plugin's tasks run where they should and the config read cleanly, then uploads a report to mclo.gs (passwords and tokens removed) to share when asking for help.
- The gamemodes' names, flight's "enabled" and "disabled" and the speed types are words in `lang/`, so they're translated too.

### Changed

- "No player named X is online" is "No player called X is online".
- A command whose alias another xUtilities command already has keeps its name and loses the alias, with a warning, instead of the reload failing.
- bStats (Bukkit only: bStats has no Fabric, NeoForge or Forge platform) and the update checker are the core's now, as above; `plugins/bStats/config.yml` still turns bStats off for every plugin.

## 2.1.0

Replace the jar and restart. Your config is kept as it is.

### Added

- A developer API, so other plugins can work with xUtilities: they can read, set and clear where `/back` takes a player (a teleport plugin can make `/back` work for its own teleports, a minigame can stop `/back` returning into it), and check which commands are on. See [Developer API](https://plugins.xenastudios.co/xutilities/developer-api/). It costs nothing unless a plugin uses it.

### Fixed

- `/xutilities reload` works on an empty server. Paper pauses a server that's been empty for a minute, and the reload waited for the next player to join.

## 2.0.2

Replace the jar and restart. Your config is kept as it is.

### Fixed

- A link command named `xutilities` or `xu` is skipped with a warning, like any other clash. Before, it replaced `/xutilities` itself, `reload` with it, so only a restart brought it back.

## 2.0.1

Replace the jar and restart. Your config is kept as it is.

### Fixed

- `/back` no longer keeps a spot for a player another plugin teleported or killed as they left (spawn-on-quit and combat-log plugins), which was never cleared.
- A Minecraft command that two of xUtilities' commands both took over (through aliases) is given back once both are off. Before, it could stay gone until a restart.
- A config file that can't be saved back (a read-only folder, or a mounted volume that can't replace files in one step) no longer loses your settings: they're used as written, with a warning, and backups don't pile up on every start.
- Clicking the link in the update notice now opens the new version's Modrinth page. Before, it opened nothing.
- `/setrotation` says the angles as you typed them: `12.3`, not `12.300000190734863`.

## 2.0.0

A rewrite. Replace the jar and restart: your 1.x config is carried forward, and the old file is kept as `config.yml.bak-v1`.

**Needs Paper 1.21.11 or newer** (Purpur and Folia too) and Java 21. 1.x ran on 1.20.6 and newer.

### Everything applies on reload

- `/xutilities reload` now applies every setting. Turning a command on or off, its aliases and permission, and adding, removing or renaming link commands no longer need a restart.
- A config file that isn't valid YAML no longer loses your settings: xUtilities says where the error is and keeps running with the settings it had (or the defaults, at startup) until it's fixed.
- `/xutilities reload` says how many settings fell back to their defaults, and `/xutilities info` shows which commands are on, whether an update is out, and any config problems.

### New

- A `theme` at the top of the config: a prefix and four colours every message uses, so five lines restyle the whole plugin. The messages have a new look to match. If you never changed a 1.x message, it gets the new look; any you changed are kept. Text with no colour of its own is now `theme.base` (gray) rather than white: set `base: "<white>"` to keep the old look.
- Workstation menus, `/enderchest` and `/trash` can play a sound as they open. `/enderchest` and `/trash` play their blocks' own sounds by default.
- `/xu`, short for `/xutilities`.
- `/stonecutter` and `/cartography` (alias `/cartographytable`) workstation menus.
- An update checker: the console, and players with `xutilities.admin` when they join, hear when a new version is on Modrinth. It sends only the plugin version, platform and Minecraft version, and `update-checker.enabled: false` turns it off. See [Privacy](https://plugins.xenastudios.co/start/privacy/).
- [bStats](https://bstats.org/plugin/bukkit/XenaUtilities/34866) anonymous usage statistics. Turn them off for every plugin in `plugins/bStats/config.yml`. See [Privacy](https://plugins.xenastudios.co/start/privacy/).
- `messages.error.usage`, shown when a command is typed wrong, and `messages.error.failed`, shown if a command hits an error.

### Changed

- `/speed` says which speed it set: "Your flying speed is now 5" or walking.
- "Player not found" says which name it couldn't find.
- A player changed by someone else (healed, fed, given flight, a gamemode and so on) is now told, with the message they'd get doing it themselves.
- `/gamemode <mode>` also needs that mode's own permission, so giving a rank `/gamemode` doesn't give it creative. It accepts names, numbers, letters and the mode commands' aliases.
- A renamed permission keeps its command's default: rename `/ping`'s and it's still everyone's.
- Players you can't see count as offline for `[player]` and tab completion, so vanished staff stay hidden.
- `/clear` and `/gamemode` replace Minecraft's own, and give them back when turned off. A command another plugin already has is left to it, with a console warning; ours stays reachable as `/xutilities:<name>`.
- `/top` won't put you on lava, fire, magma, cactus and similar blocks, or on the Nether's roof.
- On Folia, `/enderchest <player>` opens another player's chest only when they're in the same region, so two regions never edit it at once.
- An empty setting, such as `permission:` with nothing after it, now means blank instead of being replaced with the default.
- Link commands need only a `message`; everything else has a default.
- `messages.error.disabled` is gone: a command that's off doesn't exist.
