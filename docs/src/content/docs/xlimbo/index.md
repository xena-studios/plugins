---
title: xLimbo
description: "xLimbo: a free limbo and fallback server for Velocity and BungeeCord networks on Paper, where players wait while your main server is down, then go back on their own."
sidebar:
  label: Overview
  order: 1
---

xLimbo turns a small Paper server into your network's fallback. When your main server crashes, restarts or can't be reached, your proxy sends players to xLimbo instead of disconnecting them. They wait in an empty void world, in creative with flight, on a purple glass floor, then go back with `/join`, or on their own the moment the main server is back.

- **Every feature free.** No premium version, no early-access builds.
- **Built for everyone arriving at once.** Players are placed before they're in the world, on land that's already loaded, so a whole network landing at once never waits on world generation. The world is one floor layer and nothing else, and by default nothing is saved to disk.
- **Sends everyone back, without a stampede.** With [server-check](/xlimbo/network-setup/#send-everyone-back-when-its-up) on, xLimbo watches your main server and sends players back as soon as it's up, a few per second, showing each player their place in line.
- **It stays up.** A feature that fails is logged and stays off, never taking the server down. A bad config value falls back to its default.
- **Nobody can break it.** The floor can't be broken, players who fall are caught, and portals, liquids, fire, explosions, redstone, mobs and dropped items are all blocked. Each protection can be turned off, and one that's off costs nothing. See [Protections](/xlimbo/protections/).

It doesn't run on Folia: Folia can't create worlds, and a limbo has nothing for Folia to spread across threads.

:::note[Coming from xLimbo 1.x?]
Replace the jar and restart. Your config is carried forward to 2.0's format, and the old file is kept beside it as `config.yml.bak-v1`. xLimbo 2 needs Paper 1.21.11 or newer.
:::

## What else it does

- **Every change applies on reload**, even the world's name and floor. No restarts.

## Getting started

1. Run a separate Paper server for xLimbo and [install it](/start/installing/) there.
2. Point your proxy at it as the fallback, and set `main-server`: [Network setup](/xlimbo/network-setup/).
3. Turn on server-check, so players go back on their own: [Send everyone back](/xlimbo/network-setup/#send-everyone-back-when-its-up).
4. Lock down forwarding so players can't spoof `/join`: [Securing the proxy](/xlimbo/network-setup/#securing-the-proxy).
5. Tune the server for a fallback's load: [Server tuning](/xlimbo/server-tuning/).
