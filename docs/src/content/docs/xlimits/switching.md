---
title: Switching
description: "Moving to xLimits from FarmControl or Insights: what /xlimits import carries over, and what's different."
sidebar:
  order: 7
---

`/xlimits import farmcontrol` and `/xlimits import insights` read the other plugin's files beside xLimits' folder and write their limits into `plugins/xLimits/config.yml`, keeping everything you set there. Add `--dry-run` first to see what would be imported and what's skipped, then `/xlimits reload`. Importing again changes nothing. Remove the other plugin afterwards: two limiters on one server refuse each other's spawns.

## From FarmControl

FarmControl runs its profiles every few seconds on a timer and acts on the mobs it finds; xLimits checks at the moment a mob spawns or breeds, and counts per chunk.

| FarmControl | xLimits |
| --- | --- |
| A proactive profile's group (`category:animal`, `category:monster`, a type, or a list of types) | A farm rule on `animals`, `monsters`, the type, or a group of your own named after the profile |
| `count` | The rule's `threshold`. FarmControl counts within `distance` blocks; xLimits counts per chunk, so check the numbers |
| `disable-collisions` | `no-collisions` |
| `remove-awareness`, `remove-random-movement`, `remove-ai` | `no-ai` (mobs stop pathfinding but still breed, drop and can be killed) |
| `disable-breeding` | `no-breeding` |
| `kill` | A cap one under the count: new mobs are refused, none are killed |
| Reactive mode, on its MSPT thresholds | A load profile, `farmcontrol_reactive`, `mspt-above` the same thresholds, at 0.75 of every limit |

Profiles that mix categories and types, and categories xLimits has no group for, are listed as skipped.

## From Insights

| Insights | xLimits |
| --- | --- |
| A `GROUP` limit of one material | A block cap on that block |
| A `GROUP` limit of several materials | A block group of your own, named after the limit, with its cap |
| Its `entities` | An entity cap (a group of your own for several) |
| `TILE` and `PERMISSION` limits, regular expressions | Skipped: no xLimits equal |

Insights' limits per region come through its addons; with xLimits, a region plugin registers a [limit provider](/xlimits/developer-api/).
