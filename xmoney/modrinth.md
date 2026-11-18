![xMoney by Xena Studios. A glacier blue pixel coin icon and the xMoney name over a stepped horizon in glacier blue, with the headline "The economy and Vault, in one plugin."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xmoney/banner.png)

## The economy and Vault, in one plugin.

A shop, jobs or vote plugin that pays money usually needs two plugins: Vault, which is only the go-between, and an economy plugin behind it. **xMoney is both. Plugins written for Vault or VaultUnlocked find it, and the money, with no Vault jar installed, on Paper and Folia.**

**[Read the docs](https://docs.xenastudios.co/xmoney/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **One jar instead of two.** xMoney carries Vault's API and VaultUnlocked's and answers both. Plugins that depend on Vault load and work as they always have. Checked with ChestShop, EconomyShopGUI and LuckPerms.
- **No money goes missing unexplained.** Every change to every balance is a line in the ledger: who, how much, the balance after, and why ("ChestShop", "paid Alex", "given by Steve"). After a dupe, you can see exactly what happened.
- **You won't feel it running.** Every balance is in memory, so a shop asking about an offline player never waits on the disk. Changes are saved by one background thread as they happen, with no timer, and the ledger line and the balance always together, even after a crash.
- **Amounts that add up.** Money is counted in whole numbers, not floating point: 0.1 + 0.2 is exactly 0.30, and NaN, negative or enormous amounts from badly behaved plugins are refused, never turned into money.
- **Folia.** Every Vault call is safe from any thread.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

---

## What players get

- `/balance`, `/pay` (with a confirm button for big payments) and `/baltop`, for players online or not.
- Amounts typed the way people type them: `250`, `1,000`, `12.5`, `1.5k`.

## What your staff get

- `/eco give`, `take`, `set`, `reset` and `history`: every change to a player's balance, newest first, with why.
- **Imports** from EssentialsX, CMI, or whatever economy plugin is registered with Vault now, with a dry run that shows the total first.
- **Networks:** servers sharing one MySQL or MariaDB database share balances, and can never spend the same money twice.
- **PlaceholderAPI:** `%xmoney_balance%` (formatted, raw or short like `1.2k`) and the top list by rank.
- Your currency's name, symbol, decimals and format, which other plugins show too.

---

## Up and running in a minute

1. Remove Vault or VaultUnlocked (xMoney is your Vault now), drop the jar in `plugins/` and start the server. Balances are kept in SQLite in its own folder, with nothing to set up.
2. Coming from EssentialsX or CMI? Keep it installed for now and run `/xmoney import essentials` (or `cmi`, or `vault`), check the totals, then confirm and remove it.
3. Set your currency in `plugins/xMoney/config.yml` and `/xmoney reload`.

Full setup, every setting and every permission: **[docs.xenastudios.co/xmoney](https://docs.xenastudios.co/xmoney/)**

**Requires** Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. Replaces Vault: don't install Vault or VaultUnlocked beside it. PlaceholderAPI is optional. SQLite out of the box, or MySQL/MariaDB.

**Licences:** xMoney is GPL-3.0. Its jar includes the classes of [VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI) 2.20, with [Vault's API](https://github.com/MilkBowl/VaultAPI), unmodified, under the LGPL-3.0; the licence texts are in the jar.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xmoney)
