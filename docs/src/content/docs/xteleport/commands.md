---
title: Commands
description: xTeleport's commands and permissions.
sidebar:
  order: 2
---

Every player command is on for everyone by default, and every staff command for operators. Each command can be turned off, renamed with `aliases` or given another permission under `commands` in the [config](/xteleport/configuration/).

## Homes

| Command | What it does | Permission |
| --- | --- | --- |
| `/sethome [name]` | Sets a home where you stand, or moves it if you have one by that name. Without a name, it's `home`. | `xteleport.command.sethome` |
| `/home [name]` | Teleports to a home. With one home, or one called `home`, the name can be left out; otherwise it lists them. `/home bed` goes to your bed or respawn anchor. | `xteleport.command.home` |
| `/delhome <name>` | Deletes a home. | `xteleport.command.delhome` |
| `/homes [page]` | Lists your homes. Click one to go there. | `xteleport.command.homes` |
| `/home <player>:<name>`, `/delhome <player>:<name>`, `/homes <player>` | The same for another player's homes, online or not. | `xteleport.homes.others` |

Names are 1 to 32 letters, digits, `-` or `_`, in any case: `/home Base` and `/home base` are the same home.

## TPA

| Command | What it does | Permission |
| --- | --- | --- |
| `/tpa <player>` | Asks to teleport to a player. They get a message with **Accept** and **Deny** to click. | `xteleport.command.tpa` |
| `/tpahere <player>` | Asks a player to teleport to you. | `xteleport.command.tpahere` |
| `/tpaccept [player]` (`/tpyes`) | Accepts the newest request, or the one from that player. | `xteleport.command.tpaccept` |
| `/tpdeny [player]` (`/tpno`) | Denies it. | `xteleport.command.tpdeny` |
| `/tpacancel [player]` | Takes back the requests you sent, or the one to that player. | `xteleport.command.tpacancel` |
| `/tptoggle` | Stops or starts requests to you. Kept through restarts. | `xteleport.command.tptoggle` |

Whoever sends the request pays its cost and has its cooldown, whichever of the two moves. The player who moves has the warmup.

## Warps and spawn

| Command | What it does | Permission |
| --- | --- | --- |
| `/warp [name]` | Teleports to a warp. Without a name, lists them. | `xteleport.command.warp` |
| `/warps [page]` | Lists the warps. Click one to go there. | `xteleport.command.warps` |
| `/setwarp <name>` | Sets a warp where you stand, or moves it. | `xteleport.command.setwarp` (operators) |
| `/delwarp <name>` | Deletes a warp. | `xteleport.command.delwarp` (operators) |
| `/spawn` | Teleports to the spawn. | `xteleport.command.spawn` |
| `/setspawn` | Sets the spawn where you stand. | `xteleport.command.setspawn` (operators) |

## Random teleport

| Command | What it does | Permission |
| --- | --- | --- |
| `/rtp [world]` (`/wild`) | Teleports to a random safe spot, in your world or the one named. Only worlds listed under `rtp.worlds` can be picked. It has a 5-minute cooldown by default. | `xteleport.command.rtp` |

## Staff

| Command | What it does | Permission |
| --- | --- | --- |
| `/xteleport reload` (`/xtp`) | Reloads the config. Everything applies except `storage`. | `xteleport.admin` |
| `/xteleport info` | The version, the commands that are on, and any config problems. | `xteleport.admin` |
| `/xteleport import essentials [confirm]` | Imports homes, warps and the spawn from EssentialsX. Without `confirm`, only says what it found. See [Switching](/xteleport/switching/). | `xteleport.admin` |
| `/xteleport rtpcheck <world> [searches]` | Searches like `/rtp` without teleporting anyone, and says how often it found a spot and how long it took. For checking a world's RTP settings. | `xteleport.admin` |

## Other permissions

All of these are for operators by default.

| Permission | What it does |
| --- | --- |
| `xteleport.homes.<n>` | Allows `<n>` homes, such as `xteleport.homes.10`. The highest a player has wins. |
| `xteleport.homes.unlimited` | Any number of homes. |
| `xteleport.warmup.bypass` | Teleports at once, with no warmup. |
| `xteleport.cooldown.bypass` | No cooldowns. |
| `xteleport.cost.bypass` | Teleports cost nothing. |
| `xteleport.tptoggle.bypass` | Sends requests to players who turned them off. |
| `xteleport.warp.<name>` | Uses that warp, when `warps.per-warp-permission` is on. |

## Placeholders

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed:

| Placeholder | What it shows |
| --- | --- |
| `%xteleport_homes%` | How many homes the player has. |
| `%xteleport_homes_limit%` | How many they may have, or ∞. |
| `%xteleport_warps%` | How many warps there are. |
| `%xteleport_requests%` | TPA requests waiting for them. |
| `%xteleport_cooldown_<command>%` | Seconds until they can use it again, 0 when they can: `%xteleport_cooldown_rtp%`. |
