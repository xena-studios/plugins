---
title: Requirements
description: What each plugin needs to run.
sidebar:
  order: 1
---

Every plugin runs on **Paper** and its forks (Purpur, Pufferfish), with a **Java 21** runtime. None of them needs another plugin.

| Plugin | Paper | Folia | Java |
| --- | --- | --- | --- |
| [xUtilities](/xutilities/) | 1.21.11 or newer | Yes | 21 |
| [zChat](/zchat/) | 1.20.6 or newer | Yes | 21 |
| [xLimbo](/xlimbo/) | 1.18.2 or newer | No | 21 |

Each plugin's next major version needs Paper 1.21.11 or newer, the same for all three: xUtilities 2 already does.

Spigot and CraftBukkit aren't supported: the plugins rely on APIs only Paper has.

**Folia** runs the same xUtilities and zChat jars as Paper. There's nothing to configure: they make every change to a player on the region that owns that player.

**Optional:** zChat fills in [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) placeholders in chat formats when PlaceholderAPI is installed. Without it, they aren't filled in.
