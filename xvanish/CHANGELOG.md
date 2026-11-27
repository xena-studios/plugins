# xVanish changelog

## 1.0.0 (unreleased)

Not released yet: 1.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release).

The first release: vanish for staff on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), in eight languages.

### Added

- **`/vanish`** (`/v`, `/sv`, `/pv`): vanish and show again, `on`, `off`, another player, `list`, `login` and `logout` (fake join and quit messages), and `pickup`.
- **Hidden everywhere a player could notice:** the world and the tab list (Bukkit's `hidePlayer`, so other plugins' `canSee` is false), join and quit messages with fake ones as you vanish and show, the server list's count and sample, the locator bar, death and advancement messages, sleeping and mob spawning.
- **No trace:** mobs don't target, no pickup, no pressure plates, tripwires, farmland or turtle eggs, no sculk, no collisions, projectiles pass through, no raids. Each one a switch.
- **Silent containers:** a read-only copy of a chest, barrel or shulker box, with no lid moving and no sound; sneak to open it as usual.
- **While vanished:** flight, invulnerability and no hunger, optional night vision, and a boss bar saying you're vanished.
- **Levels:** `xvanish.see` and `xvanish.level.<n>`; with xBans, its staff see level 1.
- **Stays on** through relogs, restarts and crashes (`vanished.txt`); `xvanish.join` joins vanished every time.
- **A held-back first chat message** while vanished, sent when said again.
- **Networks:** who's vanished shared through one MySQL or MariaDB database: a player vanished on one server switches to the next still hidden, `/vanish list` names those on other servers, staff see them by level there too, and a server that can't reach the database keeps everyone vanished as they were last known.
- **The family:** zChat, xTeleport and the other Xena plugins leave vanished players out.
- **For other plugins:** the `vanished` metadata, PlaceholderAPI's `%xvanish_vanished%`, `%xvanish_online%`, `%xvanish_count%` and `%xvanish_level%`, and a developer API with events.
- **Switching:** `/xvanish import supervanish` and `premiumvanish`, and their `sv.*` and `pv.*` permissions honoured on request, their layered `sv.use.level<n>` and `sv.see.level<n>` among them.
- `/xvanish reload`, `debug` and `forget`.
