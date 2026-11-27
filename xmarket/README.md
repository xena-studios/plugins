# xMarket

**A server shop, with sell and worth.**

xMarket is a server shop: players buy and sell items in a menu, with a complete, balanced price list out of the box and every feature free. It runs on Paper, Folia, Spigot and CraftBukkit from 1.8.8, and on Fabric 1.20.1, 1.21.11 and 26.3.

- **A price list for every item**, 1,812 in 16 categories, where every crafted item costs what its recipe does, so no buy-craft-sell loop pays. `/shop audit` finds any your own edits make.
- **Every feature free:** items with their full data, command entries, item and level currencies, limits, discounts and boosts by permission, history and stats.
- **Works out of the box:** install it with [xMoney](../xmoney/) (or any Vault economy) and `/shop` works.
- **No dupes by design:** shop menus hold no real items, buying takes the money before it makes the items, selling takes the items before it pays, and `/sellgui`'s chest gives back or sells everything as it closes.
- **Imports** from EconomyShopGUI, ShopGUI+ and EssentialsX's worth.yml.

**[Docs](https://plugins.xenastudios.co/xmarket/next/)** · [Commands](https://plugins.xenastudios.co/xmarket/next/commands/) · [The price list](https://plugins.xenastudios.co/xmarket/next/price-list/) · [Developer API](https://plugins.xenastudios.co/xmarket/next/developer-api/) · [Changelog](CHANGELOG.md)

Not released yet: 1.0 ships with the other plugins' rewrites.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xmarket:common:build :xmarket:bukkit:build      # tests on the JVM and wasmWasi (the loop test among them), the R8 jar and its budget
./gradlew :xmarket:common:jvmTest -PupdateGolden          # after changing xmarket/prices/: the shipped shops/*.yml and recipes.txt
./gradlew -p core/platform-fabric :xmarket:26.3:collect    # the Fabric jar for one Minecraft line, in core/platform-fabric/build/libs/
xmarket/bukkit/boot.sh paper 26.3 --java <JDK 25 home>     # the boot checks on a real server (--xmoney and the client checks for the family test)
./gradlew :xmarket:api:updateKotlinAbi                     # after a deliberate API change
./gradlew :xmarket:common:updateXenaManifest               # after changing a command, setting or message: the docs' reference
```

How the price list is made, and how to bring it to a new Minecraft version, is in [prices/README.md](prices/README.md).
