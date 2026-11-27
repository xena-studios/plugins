# xMarket's price list

What `shops/*.yml` in the jar is made from, and how to bring it to a new Minecraft version. The
plugin writes those files to `plugins/xMarket/shops/` on its first start; an owner edits them there
(or in game with `/shop edit`).

## How prices are made

- **`base.txt`**: the buy price of each item nothing is made from (and a few that are, where the
  recipe isn't how players get them), on one scale of how much time and risk one takes to get in
  survival: 1 for a block anyone digs by the stack, 4 a log, 5 coal, 20 iron, 40 gold, 100 an
  emerald, 200 a diamond, thousands for boss and once-a-world loot. The file's header has the
  whole scale.
- **Every other item** costs the cheapest way to make it from priced items through any chain of
  recipes: `vanilla/<version>/recipes.txt` (crafting, every furnace, the stonecutter, smithing)
  and `processes.txt` (what players do by hand: concrete setting, logs stripped, copper weathering,
  buckets filled, and brewing), less what a recipe hands back (a milk bucket's bucket), rounded up
  to a cent (`Pricing` in `common/`).
- **Enchanted books** cost a book plus the enchanting table's levels for that level (`enchantments.txt`),
  10 a level, more for rarer enchantments, three times for treasure ones.
- **Sell prices** are 30% of the buy price, rounded down to a cent.
- **Left out:** what no survival player can get (`common/src/commonMain/resources/xmarket/unobtainable.txt`),
  and spawn eggs. `sell-only.txt` is bought but never sold (the dragon egg).

So nothing sells for more than it costs to buy or make, and `PriceListTest` checks it for every
recipe of 1.12.2, 1.13.2, 1.16.5, 1.20.1, 1.21.11 and 26.3: an item that sells for more than its
cheapest way to be had is a loop, and any buy-craft-sell profit ends in one. `/shop audit` runs
the same check on a server's own prices against the newest version's recipes.

## A new Minecraft version

1. Run Mojang's data generator on the new server jar, and read its items and recipes:
   ```sh
   java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --server --output out
   node xmarket/prices/extract.ts --version 26.4 --data out
   ```
2. Point `PriceSources.NEWEST` (and its `versions`) at it.
3. Run `./gradlew :xmarket:common:jvmTest`: `everyObtainableItemHasAPrice` lists the new items with
   no base price and no recipe. Add each to `base.txt` (or `unobtainable.txt`), and a rule to
   `Generator`'s categories if one lands in the wrong place.
4. `./gradlew :xmarket:common:jvmTest -PupdateGolden` writes the jar's `shops/*.yml` and
   `recipes.txt`; commit them with the rest. Items Minecraft renamed go in `ItemAliases`.

## Servers before 1.13

`legacy.txt` (shipped in the Bukkit jar) names each of 1.8 to 1.12's materials and data values by
today's id: `WOOL:14 red_wool`. It was made with Mojang's own data fixer from 26.3's server jar,
which turns any 1.12 item stack into today's: for each of 1.12.2's materials (from Paper's 1.12.2
API) and data values 0 to 15, an item stack `{id: <numeric id>, Damage: <data>}` at data version
99 updated to 26.3's, keeping each id that's an item today and differs from the material's data
0. 1.12.2's own recipes (`vanilla/1.12.2/`) were read the same way, from the recipe files in its
server jar.
