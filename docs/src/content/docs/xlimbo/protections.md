---
title: Protections
description: What xLimbo blocks so players can't break, lag or escape the fallback.
sidebar:
  order: 4
---

Players in xLimbo are in creative, so xLimbo blocks everything that could lag, break or escape the server. Every protection is on by default except the build guard, and each can be turned off under `protections`.

| Setting | Default | What it blocks |
| --- | --- | --- |
| `floor` | on | Breaking, replacing or building over the floor. |
| `void.enabled` | on | Falling forever: players below `threshold-y` (default `0`) are put back on the floor. |
| `void.cancel-void-damage` | on | Void and fall damage, so a rescue never becomes a death loop. |
| `portals` | on | Nether and End portals, and Eyes of Ender in End portal frames. |
| `liquids` | on | Placing water and lava, and liquids flowing. |
| `gravity-blocks` | on | Sand, gravel, anvils, concrete powder and other blocks that fall. |
| `fire` | on | Fire spreading, burning and being lit. |
| `explosions` | on | Every explosion, from blocks or entities. |
| `mob-spawns` | on | Mobs spawning. |
| `redstone` | on | Redstone activity. |
| `item-drops` | on | Dropped items, so building doesn't leave litter. |
| `entities.block-entity-items` | on | Armour stands, boats, minecarts, item frames, paintings, End crystals and spawn eggs. |
| `entities.cleanup-interval-seconds` | `300` | Removes stray non-player entities this often. `0` turns the sweep off. |
| `build-guard.enabled` | **off** | Lag machines: limits each player to `max-places-per-second` blocks (default `30`). |
