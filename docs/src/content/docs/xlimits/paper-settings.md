---
title: Recommended Paper settings
description: "Which of Paper's and Spigot's own limits to tune before xLimits, and why each xLimits feature isn't a copy of one."
sidebar:
  order: 6
---

Paper and Spigot already ship a lot of limits. Where they do the job, xLimits doesn't copy it: tune these first. `/xlimits here` shows the ones that apply to your chunk beside xLimits' counts.

| Setting | What to do | What xLimits does next to it |
| --- | --- | --- |
| `collisions.max-entity-collisions` (Paper, 8; Spigot's `max-entity-collisions`) | Lower it to 2 to 4 on a survival server. | It applies to every mob everywhere. Farm slowing's `no-collisions` only touches a crammed farm past its threshold, and never named, tamed or leashed mobs. |
| `chunks.entity-per-chunk-save-limit` | Set it for arrows, XP orbs and snowballs only. | It **deletes** the extras when the chunk saves; set for mobs, it silently loses someone's animals. xLimits refuses *new* spawns and removes nothing. |
| `per-player-mob-spawns`, `spawn-limits`, `tick-rates.mob-spawner`, `nerf-spawner-mobs` | Keep `per-player-mob-spawns` on; lower `spawn-limits` if natural spawning is the problem. | They cover natural spawning and spawner speed. Nothing in Paper caps breeding, spawn eggs, dispensers or placed vehicles and armour stands per chunk, which is how most crammed farms are built. |
| `hopper-transfer`, `hopper-check`, `hopper.cooldown-when-full` | Leave Paper's defaults; `hopper.disable-move-event: true` if no plugin needs it. | They make each hopper cheaper. Nothing limits how many hoppers, observers or pistons go in one chunk: `blocks.limits` does. |
| `redstone-implementation: ALTERNATE_CURRENT` | Turn it on. | It makes redstone cheaper, but doesn't stop a clock someone left running: `redstone` does. |
| `max-tnt-per-tick` (100, server-wide), `allow-piston-duplication: false` | Leave them. | One lag machine can still use the whole server's TNT budget: `tnt.primed` caps it per chunk. |
| `entity-activation-range`, villager tick rates | Lower activation ranges a little. | They slow far-off mobs. A farm with a player standing beside it is untouched: farm slowing is for that farm. |
| `/paper entity list` | Use it. | It counts entities, not blocks or redstone, and not against a limit. `/xlimits here`, `top` and the alerts do. |

What Paper can't do at all: limits per world and per area, players told why, limits that change while the server runs (reload, load profiles), staff alerts with a teleport and an xRewind lookup, an API, and a way back (`/xlimits restore`).
