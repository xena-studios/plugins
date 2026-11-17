---
title: Commands
description: xRewind's commands, the lookup parameters, and permissions.
sidebar:
  order: 2
---

Every command is `/xrewind <subcommand>`, or `/xr` for short. Each has its own permission, all given to operators by default, so you can hand staff only the ones they need.

| Command | What it does | Permission |
| --- | --- | --- |
| `/xr inspect` (`i`) | Turns the inspector on or off. Left-click a block to see its history, or place a block to see what was at that spot. Nothing is broken or placed while it's on. | `xrewind.inspect` |
| `/xr lookup <params>` (`l`) | Searches the history, newest first, 7 to a page. | `xrewind.lookup` |
| `/xr page <number>` | Another page of your last lookup. | `xrewind.lookup` |
| `/xr near` | What changed within 5 blocks in the last 3 days. | `xrewind.lookup` |
| `/xr status` | Where the history is kept, how much has been saved and is waiting, and any problem. | `xrewind.status` |
| `/xr reload`, `/xr info` | Reload the config, or show the version and settings. | `xrewind.admin` |

## Lookup parameters

The same as CoreProtect's, so what you know already works. Combine as many as you like: `/xr l u:Steve t:2h r:20 a:-block`.

| Parameter | Means | Examples |
| --- | --- | --- |
| `u:` | Who: players, or sources like `#tnt` and `#creeper`. Several with commas. | `u:Steve`, `u:Steve,Alex`, `u:#tnt` |
| `t:` | How far back, in `w` `d` `h` `m` `s`. | `t:30m`, `t:2w3d` |
| `r:` | How far from you, in blocks; or `#world` for the whole world, `#global` for every world. Without it, your whole world. | `r:20`, `r:#global` |
| `a:` | What kind of change. | `a:block`, `a:+block` (placed), `a:-block` (broken), `a:session` |
| `i:` | Only these blocks: what was broken, or what was placed. | `i:diamond_ore,chest` |
| `e:` | Leave out these blocks or players. | `e:dirt,stone`, `e:Steve` |
| `w:` | Another world than yours. | `w:world_nether` |
| `#count` | Only how many changes match. | |

From the console there's no "you" to measure from, so add `w:<world>` or `r:#global`.
