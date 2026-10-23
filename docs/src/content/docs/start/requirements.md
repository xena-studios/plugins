---
title: Requirements
description: What each plugin needs to run.
sidebar:
  order: 1
---

Every plugin runs on **Paper** and forks of it such as **Purpur**, with **Java 21 or newer**. Paper 26.x itself needs Java 25, so that's what a 26.x server runs on. None of the plugins needs another plugin.

| Plugin | Paper | Folia | Java |
| --- | --- | --- | --- |
| [xUtilities](/xutilities/) | 1.21.11 or newer | Yes | 21 or newer |
| [zChat](/zchat/) | 1.21.11 or newer | Yes | 21 or newer |
| [xLimbo](/xlimbo/) | 1.21.11 or newer | No | 21 or newer |

Spigot and CraftBukkit aren't supported: the plugins rely on APIs only Paper has.

**Folia** runs the same xUtilities and zChat jars as Paper. There's nothing to configure: they make every change to a player on the region that owns that player. xLimbo doesn't run on Folia: Folia can't create worlds, and a fallback server has nothing for Folia to spread across threads.

**Optional:** zChat fills in [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) placeholders in chat formats when PlaceholderAPI is installed. Without it, they show nothing, and the console says so.
