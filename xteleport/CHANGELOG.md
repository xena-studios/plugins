# xTeleport changelog

## 1.0.0

The first release. Drop the jar in and start the server: homes and warps are kept in SQLite in its own folder, with nothing to set up. Coming from EssentialsX? Run `/xteleport import essentials` to see what it would bring, then add `confirm`.

### Homes

- `/sethome [name]`, `/home [name]`, `/delhome <name>` and `/homes`, a list to click. With one home, or one called `home`, the name can be left out.
- 3 homes each by default. `xteleport.homes.<n>` and `homes.limits` in the config raise it, by permission or LuckPerms group; `xteleport.homes.unlimited` lifts it.
- `/home bed` goes to a player's bed or respawn anchor, without counting towards the limit.
- Staff use anyone's homes, online or not: `/home <player>:<name>`, `/delhome <player>:<name>`, `/homes <player>`.

### TPA

- `/tpa`, `/tpahere`, and `/tpaccept` and `/tpdeny` with buttons to click. Several requests can wait at once: answer the newest, or name a player.
- `/tpacancel` takes back what you sent; `/tptoggle` stops requests, and is kept through restarts.
- Players you can't see, such as vanished staff, can't be sent requests.

### Warps and spawn

- `/setwarp`, `/warp`, `/delwarp` and `/warps`. Optionally, each warp needs `xteleport.warp.<name>`.
- `/setspawn` and `/spawn`. New players arrive at the spawn, and players who die without a bed or anchor respawn there; each can be turned off, or every join sent there.

### Random teleport

- `/rtp [world]`: a random safe spot in a ring around each world's centre, inside the world border, in the worlds you list. No lava, water, leaves, powder snow, void or oceans; under the roof in the Nether, and off the main island in the End.
- One chunk at a time, loaded in the background; at most 10 tries per search, one search per player, and 3 at once server-wide. `rtp.only-generated` keeps it to chunks that already exist.
- `/xteleport rtpcheck <world> [searches]` tries a world's settings without teleporting anyone.

### Every teleport

- A 3-second warmup that moving or taking damage cancels, counting down on the action bar.
- Cooldowns and Vault costs per command. A cost is taken after the warmup and given back if the teleport fails.
- A home, warp or spawn that's now in lava, inside blocks or over the void is refused with a message.
- Every teleport uses the command cause, so xUtilities' `/back` returns from it.

### Also

- Every command can be turned off, renamed with aliases or given another permission, and every setting except `storage` applies on `/xteleport reload`.
- Runs on Folia.
- PlaceholderAPI: `%xteleport_homes%`, `%xteleport_homes_limit%`, `%xteleport_warps%`, `%xteleport_requests%`, `%xteleport_cooldown_<command>%`.
- A developer API (`co.xenastudios:xteleport-api`): teleports with xTeleport's warmup, cooldown, cost and safety check; homes, warps and spawn; the RTP search; and events before and after teleports, for homes being set and for requests.
