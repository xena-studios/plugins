---
title: Changelog
description: "What changed in each xMarket release."
editUrl: https://github.com/xena-studios/plugins/edit/main/xmarket/CHANGELOG.md
sidebar:
  order: 90
---

## 1.0.0 (unreleased)

Not released yet: 1.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release).

The first release: a server shop on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), in eight languages.

### Added

- **`/shop`:** a menu of categories, then a category's pages; left click to buy, right click to sell, each with an amount screen (one, a stack, fill the inventory, everything you have, or type a number). `/shop <category>` opens one directly, and `/shop buy <item> [amount]` buys without the menu.
- **Selling:** `/sell hand`, `/sell all`, `/sell <item> [amount]`, and `/sellgui`, a chest to drop items into: they're sold as it closes, and anything without a price comes back. `/worth` says what the held item (or one named) sells for.
- **A complete price list:** every item a survival player can get on 26.3, 1,812 in 16 categories, with potions, splash and lingering potions, tipped arrows and enchanted books by type. Raw materials have hand-set base prices; every crafted item costs what its recipe does, and sells for 30% of that, so nothing can be bought, crafted and sold for a profit. A test proves it for every recipe of 1.12.2, 1.13.2, 1.16.5, 1.20.1, 1.21.11 and 26.3. One `shop.multiplier` scales every price.
- **`/shop audit`:** the items this server has that have no price, and every buy-craft-sell loop your own edits made, with the recipe that makes it.
- **Editing in game:** `/shop edit add` adds the item in your hand, with all its data (a named sword, another plugin's item), and `price`, `move` and `remove` the rest; the category files stay readable, one per category.
- **Command entries:** an entry can run commands (a rank, a kit, a key) with its own icon and name.
- **Rules per item or category:** a permission to see and use it, buy-only or sell-only, limits per player or for the whole server per day, week or ever, and requirements through PlaceholderAPI (`"%player_level% >= 10"`).
- **Discounts and sell boosts** by permission: `xmarket.discount.<n>` and `xmarket.sellboost.<n>`.
- **Currencies:** money through xMoney, any economy on Vault, or the Common Economy API on Fabric; an item currency (`item:diamond`) that needs no economy at all; or experience levels. A category can use its own.
- **History and stats:** `/shop history [player]`, and `/shop stats [days]`: what the shop paid out and took in, so what it added to or took out of the economy, and what sold most.
- **Imports:** `/xmarket import economyshopgui`, `shopguiplus` and `essentials` (worth.yml), with `--dry-run` first.
- **`/xmarket reload`, `debug`, `import` and `forget <player>`.**
- **For developers:** `XMarket.api()`: prices from memory, purchases and sales with every rule applied, price modifiers and price providers, and the shop's events (`ShopBuyEvent`, `ShopSellEvent`, `ShopBoughtEvent`, `ShopSoldEvent`), on Bukkit also as Bukkit events.
