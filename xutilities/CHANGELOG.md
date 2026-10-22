# xUtilities changelog

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
- An update checker: the console, and players with `xutilities.admin` when they join, hear when a new version is on Modrinth. It sends only the plugin version, platform and Minecraft version, and `update-checker.enabled: false` turns it off. See [Privacy](https://docs.xenastudios.co/start/privacy/).
- [bStats](https://bstats.org/plugin/bukkit/XenaUtilities/34866) anonymous usage statistics. Turn them off for every plugin in `plugins/bStats/config.yml`. See [Privacy](https://docs.xenastudios.co/start/privacy/).
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
