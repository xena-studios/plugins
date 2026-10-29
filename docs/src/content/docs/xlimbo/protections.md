---
title: Protections
description: What xLimbo blocks so players can't break, lag or escape the fallback.
sidebar:
  order: 4
---

Players in xLimbo are in creative, so xLimbo blocks everything that could lag, break or escape the server. Every protection is on by default except the build guard, and each can be turned off under `protections`. A protection that's off isn't listening at all, so it costs nothing; one that's on acts only in the limbo world.

| Setting | Default | What it blocks |
| --- | --- | --- |
| `floor` | on | Breaking the floor, pushing it with pistons, building in or under it, blowing holes in it (even with `explosions` off), and it melting or decaying. |
| `void.enabled` | on | Falling forever: players below `threshold-y` (default `0`) are put straight back on the floor above them. |
| `void.cancel-void-damage` | on | Void and fall damage, so a rescue never becomes a death loop. |
| `portals` | on | Nether and End portals, and Eyes of Ender in End portal frames. |
| `liquids` | on | Placing water and lava, and liquids flowing. |
| `gravity-blocks` | on | Placing sand, gravel, anvils, concrete powder and other blocks that fall; one placed another way stays put. |
| `fire` | on | Fire spreading, burning and being lit. |
| `explosions` | on | Every explosion, from blocks or entities. |
| `mob-spawns` | on | Mobs spawning, except ones another plugin spawns on purpose. |
| `redstone` | on | Redstone activity, pistons and dispensers. |
| `item-drops` | on | Dropped items, so building doesn't leave litter. |
| `entities.block-entity-items` | on | Armour stands, boats, minecarts, item frames, paintings, End crystals and spawn eggs. |
| `entities.cleanup-interval-seconds` | `300` | Removes stray entities this often: dropped items, mobs, vehicles, projectiles. Players, armour stands, item frames, paintings, display, marker and interaction entities, and anything tagged `xlimbo.keep` are left alone. `0` turns the sweep off. |
| `build-guard.enabled` | **off** | Lag machines: limits each player to `max-places-per-second` blocks (default `30`). |
