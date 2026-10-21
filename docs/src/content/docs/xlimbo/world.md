---
title: The world and players
description: The void world, its floor and border, and what players see when they arrive.
sidebar:
  order: 3
---

## The world

xLimbo creates its own world on startup, named `xlimbo` by default. It's a void with one shared floor layer, unbreakable, and nothing else: no terrain, caves, structures or mobs. Every chunk costs the same, however far players go.

```yaml
world:
  name: "xlimbo"         # restart to change
  floor-block: "GLASS"   # restart to change
  floor-y: 64            # restart to change; players spawn one block above it
  auto-save: false
  reset-on-startup: false
  border:
    size: 10000
    spawn-radius: 4000
  view-distance: 4
  simulation-distance: 4
```

- **`auto-save: false`** keeps the world off disk entirely, which is what you want for a fallback. Set it to `true` only if builds there should survive a restart.
- **`reset-on-startup: true`** deletes the world folder on startup, so each restart begins fresh. It never runs while players are on.
- **`border.size`** is the width of the square world border, in blocks. It caps how much world can ever be generated.
- **`border.spawn-radius`** spreads arriving players up to this many blocks from the centre, so they don't spawn on top of each other. Keep it well inside the border; xLimbo shrinks it if it's too large.
- **`view-distance`** and **`simulation-distance`** apply to this world. `-1` keeps the server's default.

### Using the server's main world instead

To make the server's default world the limbo (a single-world server), set `world.name` to it (usually `world`, as in `level-name` in `server.properties`) and give it xLimbo's generator in `bukkit.yml`:

```yaml
worlds:
  world:
    generator: xLimbo
```

`floor-y` and `floor-block` still apply. `reset-on-startup` doesn't, because the server loads its default world before xLimbo can delete it.

## Arriving players

Joining players are teleported to a random spot inside `spawn-radius`. The chunk is loaded before they arrive, so the join never stalls the server.

```yaml
player:
  set-creative: true
  enable-flight: true
join:
  suppress-join-message: true
  custom-join-message: "<gray>Welcome to <aqua>xLimbo</aqua>. Type <yellow>/join</yellow> to return to the server.</gray>"
  clear-chat-on-join: false
quit:
  suppress-quit-message: true
death:
  suppress-death-message: true
action-bar:
  enabled: true
  interval-ticks: 40
  message: "<gray>Use <yellow>/join</yellow> to join the server!</gray>"
```

- Players are put in **creative** with **flight**, which stays on so nobody falls.
- The usual join, quit and death broadcasts are hidden, so a crash doesn't flood chat.
- The **action bar** repeats a hint every `interval-ticks` (20 ticks is one second).
