---
title: xLimbo
description: Never drop a player again. A fallback server for Paper networks.
sidebar:
  label: Overview
  order: 1
---

**Never drop a player again.**

xLimbo turns a small Paper server into your network's fallback. When your main server crashes, restarts or can't be reached, your proxy sends players to xLimbo instead of disconnecting them. They wait in an empty void world, in creative with flight, on a glass floor, then go back with `/join`, or automatically.

- **It stays up.** Every feature is wrapped so a failure is logged and skipped, never taking the server down. A bad config value falls back to its default.
- **Cheap no matter how far players roam.** The world generates one floor layer and nothing else, a world border caps how far it goes, and nothing is saved to disk.
- **Nobody can break it.** The floor can't be broken, players who fall are caught, and portals, liquids, fire, explosions, redstone, mobs and dropped items are all blocked. Each protection can be turned off.

Current version: **1.0.1**, for Paper 1.18.2 or newer (or Purpur or Pufferfish) and Java 21. It doesn't run on Folia. [Download on Modrinth](https://modrinth.com/plugin/xlimbo).

## Start here

1. Run a separate Paper server for xLimbo and [install it](/start/installing/) there.
2. Point your proxy at it as the fallback, and set `main-server`: [Network setup](/xlimbo/network-setup/).
3. Lock down forwarding so players can't spoof `/join`: [Securing the proxy](/xlimbo/network-setup/#securing-the-proxy).
4. Tune the server for a fallback's load: [Server tuning](/xlimbo/server-tuning/).
