![xMoney by Xena Studios. An emerald pixel coin icon and the xMoney name over a stepped horizon in emerald, with the headline "The economy and Vault, in one plugin."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xmoney/banner.png)

## The economy and Vault, in one plugin.

A shop, jobs or vote plugin that pays money usually needs two plugins: Vault, which is only the go-between, and an economy plugin behind it. **xMoney is both: plugins written for Vault or VaultUnlocked find it, and the money, with no Vault jar installed.** Balances, `/pay` and `/baltop`, with a ledger of every change, on Paper and Folia.

**[Read the docs](https://plugins.xenastudios.co/xmoney/)** · **[Switching to xMoney](https://plugins.xenastudios.co/xmoney/switching/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **One jar instead of two.** xMoney carries Vault's API and VaultUnlocked's and answers both. Plugins that depend on Vault load and work as they always have. Checked on real servers with ChestShop, EconomyShopGUI and LuckPerms.
- **No money goes missing unexplained.** Every change to every balance is a line in the ledger: who, how much, the balance after, and why ("ChestShop", "paid Alex", "given by Steve"). After a dupe, you can see exactly what happened.
- **You won't feel it running.** Every balance is in memory, so a shop asking about an offline player never waits on the disk. Changes are saved by one background thread as they happen, with no timer, and the ledger line and the balance always together, even after a crash.
- **Amounts that add up.** Money is counted in whole numbers, not floating point: 0.1 + 0.2 is exactly 0.30, and NaN, negative or enormous amounts from badly behaved plugins are refused, never turned into money.
- **Every change applies on reload**, except where balances are kept, which takes a restart.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia, and balances answer from memory on any thread.
- **Easy to switch to.** One command brings your balances from EssentialsX, CMI, or whatever economy plugin is registered with Vault now, with a dry run that shows the total first.

---

## What players get

- `/balance`, `/pay` (with a confirm button for big payments) and `/baltop`, for players online or not.
- Amounts typed the way people type them: `250`, `1,000`, `12.5`, `1.5k`, `2m`.
- Your currency's name, symbol, decimals and format, which every plugin that asks Vault shows too.

---

## What your staff get

- `/eco give`, `take`, `set`, `reset` and `history`: every change to a player's balance, newest first, with why.
- `/xmoney status` to check that saving is keeping up.
- **PlaceholderAPI:** `%xmoney_balance%` (formatted, raw or short like `1.2k`) and the top 100 by rank, answered from memory.
- **Networks (experimental):** servers sharing one MySQL or MariaDB database share balances, and can never spend the same money twice.

`/balance`, `/pay` and `/baltop` are for everyone. The rest has its own permission, op-only until you give it out.

---

## Up and running in a minute

1. Remove Vault or VaultUnlocked (xMoney is your Vault now), drop the jar in `plugins/` and start the server. Balances are kept in SQLite in its own folder, with nothing to set up.
2. Set your currency's name, symbol and decimals in `plugins/xMoney/config.yml`, and `/xmoney reload`.
3. Give your staff the permissions they need: `xmoney.eco` and its parts, such as `xmoney.eco.give`.

**Coming from EssentialsX or CMI?** Keep it installed for now and run `/xmoney import essentials` (or `cmi`, or `vault` for any other economy plugin). Check the count and the total, then add `confirm`, and remove the old plugin.

Full setup, every setting and every permission: **[plugins.xenastudios.co/xmoney](https://plugins.xenastudios.co/xmoney/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. Replaces Vault: don't install Vault or VaultUnlocked beside it. PlaceholderAPI is optional. SQLite out of the box, or MySQL/MariaDB.

**Licences:** xMoney is GPL-3.0. Its jar includes the classes of [VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI) 2.20, with [Vault's API](https://github.com/MilkBowl/VaultAPI), unmodified, under the LGPL-3.0; the licence texts are in the jar.

---

## More from Xena Studios

<!-- catalog:start: the other released plugins, from the brand catalog (pnpm catalog in docs/). -->
- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, every feature free, with CoreProtect's commands and your CoreProtect history.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute. Select an area and every plot and sign is made for you; players rent, add members and pay ahead.
<!-- catalog:end -->

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xmoney)
