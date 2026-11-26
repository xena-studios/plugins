# xTeleport changelog

## 2.0.0 (unreleased)

Not released yet: 2.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 1.0.1 is the release, and 1.x gets its fixes.

The rewrite: the same commands, permissions, settings, homes, warps and spawn as 1.0.1, now on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), with xTeleport's messages in eight languages. Keep the 1.0.1 jar to go back to.

### Upgrading from 1.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v1`; your homes, warps, spawn and TPA settings stay where they are (`xteleport.db`, or your MySQL tables), untouched: 2.0 reads and writes them exactly as 1.x does. See [Upgrading from 1.x](https://plugins.xenastudios.co/xteleport/upgrade/).

- **Messages are in `lang/` now**, one file per language, no longer in `config.yml`. Every message you changed in 1.x is copied into `lang/en.yml` on the first start, and then xTeleport sends English to everyone (`messages.language: en`), as 1.x did. Set it to `auto` to give each player their own language.
- **`theme` moved into `messages.theme`**, shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here. Colours you changed in 1.x stay xTeleport's own.
- The error messages (`messages.error.*`) are the core's now: `core.command.*` in `lang/`. "No player named Alex is online" reads "No player called Alex is online" unless you'd changed it.
- `update-checker` is `updates` now: `update-checker.enabled` is carried to `updates.check`.
- **Going back to 1.0.1:** stop the server, put the 1.0.1 jar back, and rename `config.yml.bak-v1` to `config.yml` (keep 2.0's as `config.yml.v2`). Your homes and warps need nothing: 1.0.1 reads the database as 2.0 left it. If you don't rename the config, 1.0.1 still starts and keeps every setting, with its own default messages.

### For developers: the API's breaking changes

- `XTeleport.api()` replaces `XTeleportApi.get()`; it's in Bukkit's services manager as before. Players are `PlayerRef`s (by UUID) and places the core's `Location`s, so one API works on every platform: from Java, `PlayerRefs.of(player.getUniqueId())`.
- `Home` and `Warp` hold a `Location`; `TeleportOptions` is built with `defaults()` or `immediate()` and `withWarmup`, `withCooldown`, `withCost` and `withCheckSafe`; `cooldownMillis` replaces `cooldown`'s `Duration`; lookups return the value or `null` rather than an `Optional`.
- The events are xTeleport's own, on `api.getEvents()` (`PreTeleportEvent.TYPE`, `TeleportEvent.TYPE`, `HomeSetEvent.TYPE`, `TpaRequestEvent.TYPE`), and on Bukkit also Bukkit events in `co.xenastudios.xteleport.api.bukkit` (`BukkitPreTeleportEvent` and the rest), which replace 1.x's.
- See [Developer API](https://plugins.xenastudios.co/xteleport/developer-api/#from-1x) for every change.

### Added

- **Spigot, CraftBukkit and Fabric**, from 1.8.8 and 1.20.1, beside Paper and Folia. Where the server can't load a chunk in the background (Spigot, CraftBukkit), xTeleport loads them on the server thread, at most one not yet loaded a tick, so a crowd typing `/rtp` can't stall it.
- **Costs through xMoney**, recorded in its ledger with the teleport as the reason, on every platform; on Bukkit without xMoney, through Vault's economy as before.
- **Translations:** xTeleport's messages in German, Spanish, French, Brazilian Portuguese, Russian, Polish and Simplified Chinese, each player in their client's language.
- **`/xteleport debug`** (health checks, the database's among them, and a report to share) and **`/xteleport forget <player>`**, which deletes a player's homes and TPA setting.
- **For other plugins:** named destinations other plugins can send players to (`registerDestination`), homes from another plugin in `/homes` and `/home` (`registerHomes`, for plot homes), and visibility hooks that keep hidden players out of `/tpa` (`registerVisibility`).

### Changed

- The database opens, and the warps are read, off the server's main thread: the server's startup doesn't wait for it.
- `/spawn` before a spawn is set goes to the main world's spawn point on top of the ground, where servers before 1.13 keep it below the surface.

## 1.0.1

### Fixed

- While Modrinth is still reviewing xTeleport, the update checker asks GitHub for its releases instead, so you hear about new versions either way. It sends GitHub only xTeleport's version, as it does Modrinth.

## 1.0.0

The first release. Drop the jar in and start the server: homes and warps are kept in SQLite in its own folder, with nothing to set up. Coming from EssentialsX? Run `/xteleport import essentials` to see what it would bring, then add `confirm`.

### Homes

- `/sethome [name]`, `/home [name]`, `/delhome <name>` and `/homes`, a list to click. With one home, or one called `home`, the name can be left out.
- 3 homes each by default. `xteleport.homes.<n>` and `homes.limits` in the config raise it, by permission or LuckPerms group; `xteleport.homes.unlimited` lifts it.
- `/home bed` goes to a player's bed or respawn anchor, without counting towards the limit.
- Staff use anyone's homes, online or not: `/home <player>:<name>`, `/delhome <player>:<name>`, `/homes <player>`.

### TPA

- `/tpa`, `/tpahere`, and `/tpaccept` and `/tpdeny` with buttons to click. Several requests can wait at once: answer the newest, or name a player. Asking the same player again before they answer doesn't send them another message. A request whose payer is on a cooldown or can't afford it isn't accepted: it stays, to accept again later.
- `/tpacancel` takes back what you sent; `/tptoggle` stops requests, and is kept through restarts.
- Players you can't see, such as vanished staff, can't be sent requests.

### Warps and spawn

- `/setwarp`, `/warp`, `/delwarp` and `/warps`. Optionally, each warp needs `xteleport.warp.<name>`.
- `/setspawn` and `/spawn`. New players arrive at the spawn, and players who die without a bed or anchor respawn there; each can be turned off, or every join sent there.

### Random teleport

- `/rtp [world]`: a random safe spot in a ring around each world's centre, inside the world border, in the worlds you list. No lava, water, leaves, powder snow, void or oceans; under the roof in the Nether, and off the main island in the End.
- One chunk at a time, loaded in the background; at most 10 tries per search, one search per player, and 3 at once server-wide. `rtp.only-generated` keeps it to chunks that already exist.
- A 5-minute cooldown from arriving, and a 30-second one (`rtp.search-cooldown`) from each search, found or not, so nobody can keep the server generating terrain.
- `/xteleport rtpcheck <world> [searches]` tries a world's settings without teleporting anyone.

### Every teleport

- A 3-second warmup that moving or taking damage cancels, counting down on the action bar.
- Cooldowns and Vault costs per command. A cost is taken after the warmup and given back if the teleport fails.
- A home, warp, spawn or TPA that would land a player in lava, inside blocks, over the void, outside the world border or more than `teleports.max-fall` blocks (3) above the ground is refused with a message. Water below is safe from any height, kelp and seagrass and waterlogged blocks included, and soul sand, mud, farmland and dirt paths are stood on, not in.
- A home, warp, spawn or `/tptoggle` the database couldn't save is undone and the player is told to try again, rather than lost on restart.
- Every teleport uses the command cause, so xUtilities' `/back` returns from it.

### Also

- Every command can be turned off, renamed with aliases or given another permission, and every setting except `storage` applies on `/xteleport reload`.
- Runs on Folia.
- PlaceholderAPI: `%xteleport_homes%`, `%xteleport_homes_limit%`, `%xteleport_warps%`, `%xteleport_requests%`, `%xteleport_cooldown_<command>%`.
- A developer API (`co.xenastudios.xteleport:xteleport-api`): teleports with xTeleport's warmup, cooldown, cost and safety check; homes, warps and spawn; the RTP search; and events before and after teleports, for homes being set and for requests.
