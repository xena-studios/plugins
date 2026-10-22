---
title: Server tuning
description: Server settings outside xLimbo that keep a fallback flat under load.
sidebar:
  order: 6
---

Some tuning lives in the server's own config files, not xLimbo's. For a fallback server that a whole network may land on at once:

## server.properties

```properties
view-distance=4
simulation-distance=4
spawn-protection=0
allow-nether=false
```

xLimbo sets view and simulation distance on its own world, but the server-wide values still apply to any other world. Set `online-mode=false` only behind a proxy with [secure forwarding](/xlimbo/network-setup/#securing-the-proxy).

## spigot.yml

Lower the `entity-tracking-range` values and `merge-radius`, and keep `view-distance` in step with `server.properties`.

## Paper

In `config/paper-global.yml`, raise `chunk-loading-basic.player-max-chunk-send-rate` only if arrivals feel slow; void chunks are tiny, so sending them is cheap.

In `config/paper-world-defaults.yml`, tighten entity activation ranges and keep `max-auto-save-chunks-per-tick` low. xLimbo keeps its world off disk anyway.

## What xLimbo already does

You don't need to tune these yourself:

- The limbo world has its own view and simulation distance (`world.view-distance`, `world.simulation-distance`).
- Arrivals land on land kept loaded around spawn, placed before they're in the world, so a mass arrival never generates chunks or teleports anyone.
- There's no weather, nothing saves to disk, no mobs can spawn in the void biome, and stray entities are swept up every five minutes.
