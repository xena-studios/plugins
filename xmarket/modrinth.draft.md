<!-- The draft of xMarket's Modrinth and Hangar page. It becomes modrinth.md in the release's pull request: the Store pages workflow publishes any */modrinth.md that changes on main, and nothing of xMarket is published before then. -->

## A server shop, with sell and worth.

Players buy and sell items in a menu, with **a complete, balanced price list out of the box** and every feature free. xMarket runs on Paper, Folia, Spigot and CraftBukkit from 1.8.8, and on Fabric (server-side: players install nothing).

**[Read the docs](https://plugins.xenastudios.co/xmarket/)** · **[Switching from EconomyShopGUI or ShopGUI+](https://plugins.xenastudios.co/xmarket/switching/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **A price list you don't have to write.** Every item a survival player can get, 1,812 of them in 16 categories, potions and enchanted books by type. Raw materials have hand-set prices; every crafted item costs what its recipe does, so nothing can be bought, crafted and sold for a profit. That's tested for every recipe of every supported version, and `/shop audit` checks your own edits the same way.
- **Every feature free.** Items with their full data, command entries (ranks, kits, keys), item and experience currencies, limits per player or for the whole server, discounts and sell boosts by permission, requirements through PlaceholderAPI, history and stats. No premium version.
- **Works out of the box.** Install it with xMoney, or any economy on Vault, and `/shop` works. No economy at all? Price a category in diamonds.
- **No dupes by design.** Shop menus hold no real items. Buying takes the money, then makes the items; selling takes the items, then pays, and gives them back if the payment fails. `/sellgui`'s chest sells or gives back everything as it closes, a quit or a death included.
- **Edit it in game.** `/shop edit add` adds the item in your hand, with all its data. The files stay readable, one per category.
- **Tune the economy.** `/shop stats` says how much the shop added to or took out of the economy this week, and what sold most. One `multiplier` scales every price.
- **Easy to switch to.** `/xmarket import` reads EconomyShopGUI's and ShopGUI+'s shops and EssentialsX's worth.yml, with a dry run first.
- **You won't feel it running.** No repeating tasks; prices and limits answered from memory; history written off the game's threads; Folia's regions respected.

---

## Commands

`/shop [category]`, `/shop buy <item> [amount]`, `/sell hand|all|<item>`, `/sellgui`, `/worth [item]`, `/shop history [player]`, `/shop stats`, `/shop audit`, `/shop edit`, and `/xmarket reload | debug | import | forget`. Every command can be renamed, given aliases or turned off.

<!-- modrinth_exclude.start -->
Not released yet: 1.0 ships with the other plugins' rewrites.
<!-- modrinth_exclude.end -->
