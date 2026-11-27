---
title: Install
description: "Install xMarket on Paper, Folia, Spigot or Fabric."
sidebar:
  order: 1
---

- **Paper, Folia, Spigot or CraftBukkit, 1.8.8 to the newest:** put `xMarket.jar` in `plugins/` and restart.
- **Fabric 1.20.1, 1.21.11 or 26.3:** put the jar for your Minecraft line in `mods/`, with Fabric API and Fabric Language Kotlin, and restart. Server-side: players install nothing.

On the first start xMarket writes `config.yml`, the price list (`shops/`, one file per category), one file per language in `lang/`, and its SQLite database `xmarket.db`, in `plugins/xMarket/` (Bukkit) or `config/xmarket/` (Fabric). The price list is read off the server thread, so `/shop` says it's loading for a moment after a start.

- **Money:** install [xMoney](/xmoney/), or on Bukkit Vault and any economy plugin, or on Fabric any mod with the Common Economy API. Without any, a category can still be paid in an item (`currency: item:diamond`) or in experience levels (`currency: xp`).
- [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) is optional, for entries' requirements.
