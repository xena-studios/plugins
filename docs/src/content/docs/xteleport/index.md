---
title: xTeleport
description: "xTeleport: homes, TPA, warps, spawn and random teleport (RTP) in one small plugin for Paper and Folia servers, with warmups, cooldowns and Vault costs."
sidebar:
  label: Overview
  order: 1
---

xTeleport is homes, TPA, warps, spawn and random teleport in one small plugin, for Paper and Folia. It's for servers that want every teleport with one config and one look, and nothing they don't use.

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **The whole set.** `/sethome`, `/home`, `/homes`, `/tpa`, `/tpahere`, `/tpaccept`, `/warp`, `/spawn`, `/rtp` and the rest. See [Commands](/xteleport/commands/).
- **Switch off what you don't use.** Every command can be turned off, renamed with aliases or given its own permission, and every setting but storage applies on `/xteleport reload`.
- **Teleports that feel good.** A warmup that moving or taking damage cancels, with a countdown on the action bar. Cooldowns per command. An optional cost through Vault, taken after the warmup and given back if the teleport fails.
- **No deaths on arrival.** A home, warp or TPA that would land a player in lava, inside blocks, over the void or high in the air is refused with a message. The spot is loaded in the background to check it, never on the server's thread.
- **Easy to switch to.** EssentialsX's command names, so players keep typing what they know, and its homes, warps and spawn imported with one command. See [Switching from EssentialsX](/xteleport/switching/).


## How it works

**Homes** belong to a player. Each player may have 3 by default; `xteleport.homes.<n>` permissions and the config's `homes.limits` raise it. With `homes.bed-home` on, `/home bed` goes to a player's bed or respawn anchor and doesn't count towards the limit.

**TPA** requests last 60 seconds. A player can have several waiting at once; `/tpaccept` takes the newest, or `/tpaccept <player>` a particular one. Players can't send requests to anyone they can't see, so vanished staff stay hidden. `/tptoggle` stops requests altogether.

**Warps** are shared by the whole server. Staff set them with `/setwarp`; turn on `warps.per-warp-permission` to need `xteleport.warp.<name>` for each.

**Spawn** is set with `/setspawn`. New players arrive there, and players who die without a bed or anchor respawn there. Until it's set, `/spawn` goes to the main world's spawn point.

**RTP** picks a spot in a ring around each world's centre, between `min-radius` and `max-radius`, inside the world border. It skips lava, water, leaves, powder snow and the void, and oceans unless you change `rtp.blocked-biomes`. The Nether is searched under its roof, and the End off its main island.

## Lightweight

- **Nothing repeats.** A warmup is a short check four times a second on the waiting player's own thread, and it ends with the warmup. Requests run out when they're next looked at, not on a timer.
- **Listeners only for what's on.** The damage listener is only registered while someone is warming up, and the spawn listener only while a spawn option is on.
- **Homes in memory.** A player's homes are read in the background when they join and kept until they leave. Changes are saved in the background, in order. Warps and the spawn are kept in memory for everyone.

## Storage

Homes and warps are kept in SQLite out of the box, in `plugins/xTeleport/xteleport.db`, or in MySQL/MariaDB. See [Configuration](/xteleport/configuration/).

## What else it does

- **Works with `/back`.** Every teleport uses the command cause, so xUtilities' `/back` (or any plugin's that does the same) returns from it.
- **Random teleport that doesn't lag.** `/rtp` loads one candidate chunk at a time in the background, checks it on the thread that owns it, and caps how many searches run at once. It can stick to chunks that already exist, for worlds pre-generated with Chunky.

## Getting started

1. [Install it](/start/installing/). For teleport costs, add Vault and an economy plugin.
2. Set warmups, cooldowns, limits and RTP's worlds: [Configuration](/xteleport/configuration/).
3. Every command and permission: [Commands](/xteleport/commands/).
4. Coming from EssentialsX? Bring everyone's homes: [Switching from EssentialsX](/xteleport/switching/).
