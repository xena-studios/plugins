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

## Paper and Pufferfish

In `config/paper-world-defaults.yml` (and `pufferfish.yml` on Pufferfish), tighten entity activation ranges, stop ticking entities far from players, and keep `max-auto-save-chunks-per-tick` low. xLimbo runs with autosave off anyway.
