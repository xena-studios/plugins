---
title: Switching
description: "Switch to xMarket from EconomyShopGUI, ShopGUI+ or EssentialsX's worth.yml: /xmarket import, with a dry run first."
sidebar:
  order: 7
---

`/xmarket import` reads another shop's files from its folder in `plugins/` and writes each of its sections as an xMarket category, `shops/<plugin>-<section>.yml`, so nothing of xMarket's own price list is overwritten: delete the default categories you don't want. Run it with `--dry-run` first: it counts what would come over and logs each item that won't, and why. Importing again replaces what the last import wrote. On Bukkit only: these are Bukkit plugins.

## From EconomyShopGUI

```
/xmarket import economyshopgui --dry-run
/xmarket import economyshopgui
```

Reads `plugins/EconomyShopGUI/sections/` (names and slots) and `shops/` (the pages of items), or the older single `shops.yml`. Items, buy and sell prices (`-1` is none), names, lore, enchantments, potions and commands come over. Settings xMarket sets its own way (limits, requirements, NBT) are logged as left out: set them in the category file.

## From ShopGUI+

```
/xmarket import shopguiplus
```

Reads `plugins/ShopGUIPlus/shops/` or `shops.yml`: item and command entries, `buyPrice` and `sellPrice`, quantities, names, lore, enchantments and potions. Materials with a data value from before 1.13 are read as the item they are today.

## From EssentialsX

```
/xmarket import essentials
```

Reads `plugins/Essentials/worth.yml` as sell prices, in one category, `essentials-worth`.
