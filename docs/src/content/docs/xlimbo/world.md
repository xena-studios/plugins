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
  name: "xlimbo"
  floor-block: "purple_stained_glass"
  floor-y: 64
  auto-save: false
  reset-on-startup: false
  border:
    size: 10000
    spawn-radius: 64
  view-distance: 4
  simulation-distance: 4
```

- **`name`** is the world's folder name: letters, digits, `_`, `-` and `.`. xLimbo marks every world it makes, and only ever deletes or rebuilds a marked one. If a world by that name already exists and xLimbo didn't make it, xLimbo leaves it alone and says so in the console: pick another name.
- **`floor-block`** is a solid block that doesn't fall, as Minecraft names it, such as `purple_stained_glass` (the default, easy to see against the void) or `glass`.
- **`auto-save: false`** keeps the world off disk: it's deleted and made fresh on every startup, which is what you want for a fallback. Set it to `true` only if builds there should survive a restart. A saved world is still made fresh if its floor changes, so it never has two floors.
- **`reset-on-startup: true`** makes a saved world fresh on startup too.
- **`border.size`** is the width of the square world border, in blocks. It caps how much world can ever be generated.
- **`border.spawn-radius`** spreads arriving players up to this many blocks from the centre. xLimbo keeps the land in this area loaded, so arrivals never wait for it to generate; the console says when it's ready. Keep it small: 64 blocks is a few dozen chunks however many players land, where xLimbo 1.x's 4000 meant fresh land for nearly every player.
- **`view-distance`** and **`simulation-distance`** apply to this world, from 2 to 32. `-1` keeps the server's default.

The world stays clear and quiet: no weather, and no advancement messages.

### Changing the world

Changing `name`, `floor-block` or `floor-y` and running `/xlimbo reload` rebuilds the world: players in it are moved out for a moment, the world is made fresh, and they're put back. Everything else applies in place.

### Using the server's main world instead

To make the server's default world the limbo (a single-world server), set `world.name` to it (usually `world`, as in `level-name` in `server.properties`) and give it xLimbo's generator in `bukkit.yml`:

```yaml
worlds:
  world:
    generator: xLimbo
```

`floor-y` and `floor-block` still apply when the world is first made. They can't change on reload, because the server's main world can't be rebuilt while the server runs: restart instead. `reset-on-startup` doesn't apply, because the server loads its main world before xLimbo can delete it.

## Arriving players

Joining players are placed at a random spot inside `spawn-radius` before they're in the world, on land that's already loaded, so there's no teleport after they join and no waiting for chunks.

```yaml
player:
  set-creative: true
  enable-flight: true
  hide-others: true
join:
  suppress-join-message: true
  custom-join-message: "<prefix>The server is unavailable right now, so you're waiting here. Type <highlight>/join</highlight> to go back."
  clear-chat-on-join: false
quit:
  suppress-quit-message: true
death:
  suppress-death-message: true
action-bar:
  enabled: true
  interval-ticks: 40
  message: "<gray>Type <highlight>/join</highlight> to go back to the server"
```

- Players are put in **creative** with **flight**, which stays on so nobody falls.
- With **`hide-others`**, players can't see each other, in the world or the tab list. Hundreds of players arriving in one spot, each tracking every other one, is the biggest load a limbo has; hidden, it's gone. Turn it off if players should see each other while they wait.
- The usual join, quit and death broadcasts are hidden, so a crash doesn't flood chat.
- The **action bar** repeats a hint every `interval-ticks` (20 ticks is one second). While a player waits in the [return queue](/xlimbo/network-setup/#pacing), it shows their place in line instead.
