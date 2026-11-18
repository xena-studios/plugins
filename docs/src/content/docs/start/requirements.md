---
title: Requirements
description: What each plugin needs to run.
sidebar:
  order: 1
---

Every plugin runs on **Paper** and forks of it such as **Purpur**, with **Java 21 or newer**. Paper 26.x itself needs Java 25, so that's what a 26.x server runs on. None of the plugins needs another plugin, except that xPlots takes rent through [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin unless you set it to take an item instead, and xVote pays money rewards through Vault when you give any.

| Plugin | Paper | Folia | Java |
| --- | --- | --- | --- |
| [xUtilities](/xutilities/) | 1.21.11 or newer | Yes | 21 or newer |
| [zChat](/zchat/) | 1.21.11 or newer | Yes | 21 or newer |
| [xLimbo](/xlimbo/) | 1.21.11 or newer | No | 21 or newer |
| [xRewind](/xrewind/) | 1.21.11 or newer | Yes | 21 or newer |
| [xPlots](/xplots/) | 1.21.11 or newer | No | 21 or newer |
| [xTeleport](/xteleport/) | 1.21.11 or newer | Yes | 21 or newer |

Spigot and CraftBukkit aren't supported: the plugins rely on APIs only Paper has.

**Folia** runs the same xUtilities, zChat, xRewind and xTeleport jars as Paper. There's nothing to configure: they make every change to a player on the region that owns that player. xLimbo doesn't run on Folia: Folia can't create worlds, and a fallback server has nothing for Folia to spread across threads. xPlots doesn't run on Folia either, for now: putting plots back and editing their signs across Folia's regions adds risk with little gain for the box servers it's made for.

**Optional:** zChat fills in [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) placeholders in chat formats when PlaceholderAPI is installed. Without it, they show nothing, and the console says so. xPlots adds its own placeholders to PlaceholderAPI when it's installed, and with [WorldGuard](https://modrinth.com/plugin/worldguard) it makes one region per field so a world-wide `build deny` doesn't block plots. xTeleport can charge for teleports through Vault, if you turn costs on; they're off by default.
