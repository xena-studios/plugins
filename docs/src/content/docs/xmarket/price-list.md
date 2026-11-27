---
title: The price list
description: "How xMarket's price list is made, so no buy-craft-sell loop pays; the category files in shops/; editing in game; and /shop audit."
sidebar:
  order: 3
---

xMarket ships a complete price list: every item a survival player can get, 1,812 entries in 16 categories, in `shops/`, one file per category. It's written there on the first start; edit it in the files or in game.

## How it's made

- **Raw materials have base prices**, set by hand on one scale of how much time and risk an item takes to get in survival: 1 for a block anyone digs by the stack (dirt, cobblestone, sand), 4 a log, 5 coal, 20 iron, 40 gold, 100 an emerald, 200 a diamond, and thousands for boss and once-a-world loot (a nether star 10,000, an elytra 20,000). Raw ores cost a little more than what they smelt to.
- **Every crafted item costs what its recipe does**: the cheapest way to make it from priced items, through any chain of recipes (crafting, furnaces, the stonecutter, smithing, brewing), and through what players do by hand (concrete setting in water, logs stripped, copper weathering, buckets filled), less what a recipe hands back (a cake's empty buckets), rounded up to a cent. A torch is a quarter of a coal and a stick; a beacon is a nether star, five glass and three obsidian.
- **Potions, splash and lingering potions and tipped arrows** cost their brewing; **enchanted books** a book and the levels the enchanting table asks for that level, more for rarer enchantments and three times for treasure ones (Mending).
- **What an item sells for is 30% of what it costs.**

So nothing sells for more than it costs to buy, or to make from what's bought, and no buy-craft-sell loop can pay, however many steps it takes. That's tested on every build, for every recipe of 1.12.2, 1.13.2, 1.16.5, 1.20.1, 1.21.11 and 26.3. The list is kept current with each Minecraft version. Items the list doesn't price: creative items, blocks that can't be picked up even with Silk Touch, spawn eggs, and items that are their data (maps, written books, firework stars). The dragon egg is bought from players but not sold.

`shop.multiplier` in `config.yml` scales every price at once: 2 doubles them all, for a server with bigger numbers. The files keep their own numbers.

## A category's file

```yaml
name: "<aqua>Ores & minerals"
icon: diamond
slot: 14
currency: money           # money, xp, or item:diamond
permission: ""            # needed to see and use the category; blank for everyone
items:
  diamond: {buy: 200, sell: 60}
  "potion{swiftness}": {buy: 8.56, sell: 2.56}
  "enchanted_book{sharpness=5}": {buy: 464, sell: 139.2}
  emerald: {buy: 100}     # no sell price: it can't be sold here
  vip:
    icon: nether_star
    name: "<gold>VIP rank"
    lore: ["Forever"]
    buy: 5000
    commands: ["lp user {player} parent add vip"]
    limit: {buy: 1, per: ever}
  rare_sword:
    item: diamond_sword
    data: |
      ...                 # the item's full data, as /shop edit add wrote it
    buy: 900
    server-limit: {buy: 10, per: week}
    requires: ["%player_level% >= 30"]
```

An entry's key names its item (`diamond`, `potion{swiftness}`) unless it says `item:`. Prices are for one, or for `amount:` items at a time. Leave `buy` or `sell` out to stop it being bought or sold. `limit` counts per player, `server-limit` for everyone, `per` a `day`, a `week` (from Monday) or `ever`; days start at midnight in `rules.timezone`. `requires` are placeholders compared with a value (`>=`, `>`, `<=`, `<`, `==`, `!=`), read through PlaceholderAPI. `commands` run as the console for each one bought, with `{player}` and `{uuid}`, and give no item. `slot` places an entry or category; the rest fill in order.

## Editing in game

With `xmarket.edit`:

- **`/shop edit add <category> [buy|-] [sell|-]`** adds the item in your hand. A plain item is added by its id; one with a custom name, lore or model, or another plugin's data, is added with all its data, and only that very item sells as it.
- **`/shop edit price <category> <item> <buy|-> <sell|->`** sets an entry's prices; `-` for none.
- **`/shop edit move <category> <item> <to>`** and **`/shop edit remove <category> <item>`**.

Each change writes the category's whole file (to a temporary file, then renamed) and applies at once.

## The audit

**`/shop audit`** (`xmarket.audit`) lists the items this server has that have no price (after a Minecraft update, or another plugin's), and every loop the shop's own prices make: an item that sells for more than it costs to buy, or to make from what's bought, with the recipe that makes it so. The default list has none; an edit can. It checks against the newest version's recipes and what players do by hand; a data pack's or another plugin's recipes aren't in it.
