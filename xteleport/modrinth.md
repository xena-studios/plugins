![xTeleport by Xena Studios. An orchid map pin over the ring it lands in, and the xTeleport name over a stepped horizon, with the headline "Every teleport your server needs."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xteleport/banner.png)

## Every teleport your server needs.

Homes, TPA, warps, spawn and random teleport in one small plugin, for Paper **and Folia**. **It's what most servers install EssentialsX for, without the other hundred commands, and with every one of them switchable.**

**[Read the docs](https://docs.xenastudios.co/xteleport/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **One plugin, not four.** Homes, TPA, warps, spawn and RTP, with one config, one look and one set of messages.
- **Runs on Folia.** Every teleport is asynchronous, every chunk is loaded in the background, and nothing assumes a main thread.
- **Switch off what you don't use.** Every command can be turned off, renamed with aliases or given its own permission, and every setting applies on reload. A command you turn off isn't registered, so another plugin can have it.
- **Nobody dies arriving.** A home or warp that's now in lava, inside blocks or over the void is refused with a message, checked after its chunk loads in the background.
- **RTP that doesn't lag.** One candidate chunk at a time, checked on its own thread, with a cap on searches at once so a crowd typing `/rtp` can't touch your TPS. Pre-generated with Chunky? Tell it to stick to chunks that already exist.
- **Bring everyone's homes** from EssentialsX, with a dry run first.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

---

## What players get

- **`/sethome`, `/home`, `/homes`:** a clickable list, and `/home bed` for their bed or anchor.
- **`/tpa` and `/tpahere`** with Accept and Deny to click. Several requests can wait at once, and `/tptoggle` turns them off.
- **`/warp`, `/warps` and `/spawn`.**
- **`/rtp`** to a safe random spot: no lava, water, leaves or oceans, under the roof in the Nether and off the main island in the End.
- **Warmups** that count down on the action bar and cancel if they move or get hurt.

---

## What your staff get

- **Limits by permission or group:** `xteleport.homes.<n>`, or `vip: 5` in the config for a LuckPerms group.
- **Cooldowns and Vault costs** per command, taken after the warmup and refunded if a teleport fails, with bypass permissions.
- **Anyone's homes, online or not:** `/home Steve:base`, `/homes Steve`.
- **Per-warp permissions**, spawn on first join, every join or respawn, and per-world RTP radii and permissions.
- **`/xteleport rtpcheck`** to try a world's RTP settings without teleporting anyone.
- **Works with `/back`:** every teleport uses the command cause, so xUtilities' `/back` returns from it.
- **PlaceholderAPI:** `%xteleport_homes%`, `%xteleport_homes_limit%`, `%xteleport_requests%`, `%xteleport_cooldown_rtp%` and more.
- **A developer API**, so your other plugins' teleports get the same warmup, cooldown, cost and safety check.

---

## Lightweight

- **Nothing repeats.** A warmup is a short check on the waiting player's own thread that ends with it. Requests run out when they're next read, never on a timer.
- **Listeners only for what's on.** The damage listener exists only while someone is warming up.
- **Homes in memory.** Read in the background as players join, saved in the background as they change.

---

## Getting started

1. Drop the jar in `plugins/` and start the server. Homes are kept in SQLite with nothing to set up, or MySQL/MariaDB.
2. Coming from EssentialsX? `/xteleport import essentials`, then `/xteleport import essentials confirm`.
3. `/setspawn`, `/setwarp shop`, and you're done. Everything else is in the [docs](https://docs.xenastudios.co/xteleport/).

Needs Paper or Folia 1.21.11 or newer (or Purpur) and Java 21 or newer. Vault and PlaceholderAPI are optional.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute. Select an area and it becomes a grid of plots with signs, rented with Vault or an item.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xteleport)
