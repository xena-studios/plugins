---
title: Performance
description: "What xLimits costs: no repeating tasks, a count per chunk at most once a second, and the measured startup, memory and jar size."
sidebar:
  order: 9
---

xLimits has to cost less than the problem it prevents, so every check is built to return early:

- **No repeating tasks.** Nothing runs on a timer; load profiles are checked as limits are, at most once a second.
- **A spawn of a kind no limit names** costs one array read, worked out once per entity type per config.
- **A chunk's entities are counted at most once a second**, and allowed spawns are added to that count meanwhile: a busy spawner costs one count a second, not one per attempt.
- **A chunk's blocks are counted once**, block entities straight from the chunk's list of them, other blocks from a snapshot counted off the server's threads, then kept up to date from placements and breaks.
- **Listeners only for what's on.** With the defaults, spawns, chunk loads and block placements are heard; redstone, TNT, portals and pets only once you turn them on.
- **Nothing stored**, so no database, no writes and no disk work.

## Measured

On Paper 26.3 (Apple M1 Pro with other servers booting beside it, so loose; CI's budgets job, on two cores, is the measure):

| | xLimits | Budget |
| --- | --: | --: |
| Startup on the server thread (median of 5 boots) | 44.3 ms | 150 ms (first plugin) |
| Metaspace in its own class loaders | 1.35 MB | 1.5 MB |
| Heap over the server without it | 2.7 MB (21 KB of its own objects) | 4 MB |
| Idle, server thread | 0 ms a tick | 0.001 ms |
| Idle, other threads | 0 ms a second | 0.5 ms |
| Jar, shrunk | 632 KiB (Bukkit), 539 to 547 KiB (Fabric) | 500 KiB target, over for a recorded reason |

Spark profiles of a server at its limits with xLimits on and off come with the alpha on our own servers, before the release.
