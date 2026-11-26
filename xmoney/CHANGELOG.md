# xMoney changelog

## 2.0.0 (unreleased)

Not released yet: 2.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 1.0.1 is the release, and 1.x gets its fixes.

The rewrite: the same commands, permissions, settings, balances and ledger as 1.0.1, now on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), with xMoney's messages in eight languages. Keep the 1.0.1 jar to go back to.

### Upgrading from 1.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v1`; your balances and ledger stay where they are (`money.db`, or your MySQL tables), untouched: 2.0 reads and writes them exactly as 1.x does. See [Upgrading from 1.x](https://plugins.xenastudios.co/xmoney/upgrade/).

- **Messages are in `lang/` now**, one file per language, no longer in `config.yml`. Every message you changed in 1.x is copied into `lang/en.yml` on the first start, and then xMoney sends English to everyone (`messages.language: en`), as 1.x did. Set it to `auto` to give each player their own language.
- **`theme` moved into `messages.theme`**, shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here. Colours you changed in 1.x stay xMoney's own.
- The error messages (`messages.error.*`) are the core's now: `core.command.*` in `lang/`.
- `update-checker` is `updates` now: `update-checker.enabled` is carried to `updates.check`.
- `/xmoney status`'s "saved since startup" and "last save" lines are gone: the core's database writer saves in batches of its own. It says how many changes are waiting, and the problem if there is one.
- **Going back to 1.0.1:** stop the server, put the 1.0.1 jar back, and rename `config.yml.bak-v1` to `config.yml` (keep 2.0's as `config.yml.v2`). Your balances and ledger need nothing: 1.0.1 reads the database as 2.0 left it. If you don't rename the config, 1.0.1 still starts and keeps every setting, with its own default messages.

### For developers: the API's breaking changes

- `XMoney.api()` replaces `XMoneyApi.get()`; it's in Bukkit's services manager as before. Players are `PlayerRef`s (by UUID), so one API works on every platform: from Java, `PlayerRefs.of(player.getUniqueId())`.
- Amounts are `Amount`s, exact ten-thousandths, instead of `BigDecimal`s: `Amounts.of(bigDecimal, Rounding.UP)` and `Amounts.toBigDecimal(amount)` convert (round down what you pay in, up what you take).
- `deposit`, `withdraw` and `transfer` take your plugin's name (`String`) instead of the `Plugin`, and the reason is required (`""` for none). Their results are `Pending`s, which are `CompletableFuture`s on the JVM.
- The events are xMoney's own, on `api.getEvents()` (`PreTransactionEvent.TYPE`, `BalanceChangedEvent.TYPE`), and on Bukkit also Bukkit events in `co.xenastudios.xmoney.api.bukkit` (`BukkitPreTransactionEvent`, `BukkitBalanceChangedEvent`), which replace 1.x's.
- `HistoryEntry.timeMillis` replaces `time` (an `Instant`).
- See [Developer API](https://plugins.xenastudios.co/xmoney/developer-api/#from-1x) for every change.

### Added

- **Spigot, CraftBukkit and Fabric**, from 1.8.8 and 1.20.1, beside Paper and Folia. On Spigot from 1.15.2 xMoney stands in for Vault as on Paper; below 1.15.2 put Vault.jar beside it. On Fabric, other mods find the economy through the **Common Economy API**.
- **Translations:** xMoney's messages in German, Spanish, French, Brazilian Portuguese, Russian, Polish and Simplified Chinese, each player in their client's language.
- **`/xmoney debug`** (health checks, the database's among them, and a report to share) and **`/xmoney forget <player>`**, which deletes a player's account, balance and ledger rows.
- **Balances in zChat's chat formats:** with zChat 4.0 installed, its formats can show `<balance>`, `<balance_raw>` and `<balance_short>`.
- **For other plugins:** transaction hooks, so an add-on can refuse a payment and say why (a frozen plot, a locked market), beside the pre-transaction event.

### Changed

- The database opens, and every balance is read, off the server's main thread: the server's startup doesn't wait for it. A player can't join until the balances are read, and a Vault call made in the moments before waits for them, as before.

## 1.0.1

### Fixed

- The config's comment on `currency.decimals` said amounts typed into commands are rounded to them. They're refused if they have more; the comment says so now. Your config gets the new comment on the next start.
- While Modrinth is still reviewing xMoney, the update checker asks GitHub for its releases instead, so you hear about new versions either way. It sends GitHub only xMoney's version, as it does Modrinth.

## 1.0.0

The first release. Remove Vault or VaultUnlocked, drop the jar in and start the server: xMoney is your economy and your Vault, with balances kept in SQLite in its own folder and nothing to set up. Coming from another economy plugin? Run `/xmoney import essentials`, `cmi` or `vault` before removing it.

### Vault, built in

- Plugins that use Vault or VaultUnlocked find xMoney without a Vault jar, on Paper and Folia. Checked with ChestShop, EconomyShopGUI and LuckPerms.
- If Vault or VaultUnlocked is still installed, Paper loads xMoney and refuses the other jar; xMoney's warning names the jar to delete.
- Vault's permission and chat APIs are included, so LuckPerms and other permissions plugins hook them as before. Without one, Vault's permission checks answer from the server's own permissions, as Vault did.

### Money

- `/balance`, `/pay` (asks to confirm at or above `confirm-above`), `/baltop`, and `/eco give|take|set|reset|history` for staff, for players online or offline.
- Every change is in the ledger: when, how much, the balance after, and why, such as "ChestShop" or "paid Alex". `/eco history` shows it. Lines older than a year are deleted; balances never depend on them.
- Amounts are whole numbers of ten-thousandths underneath, so nothing drifts. Other plugins' amounts keep four places whatever your currency's decimals, so a price paid in two parts costs what it would whole, and rounding past that never makes money. Odd amounts (NaN, negative, huge) are refused.
- The currency's name, symbol, decimals and format are in the config, and other plugins show money that way too.
- Every balance is in memory and saved as it changes, so Vault calls never wait on the disk, from any thread.

### Also

- Imports from EssentialsX, CMI and any economy registered with Vault, each with a dry run first, and a second confirmation if it would undo changes made in xMoney since.
- A name belongs to the player who joined with it last, and stays theirs after a restart: `/pay Steve` and other plugins' payments by name never reach a player who used to be called Steve.
- Networks (experimental): servers sharing one MySQL or MariaDB database share balances, and can never spend the same money twice. A shop's purchase waits at most 50 ms for the database to take it up and a few seconds for its answer, and is refused with nothing taken if the database is slow or away. A purchase reported as failed was never charged, so if the database dies at the very moment one commits, the server waits until it answers again; per-player leases (#66) would remove that case, but they aren't planned.
- PlaceholderAPI: `%xmoney_balance%` (and `_raw`, `_short`), and `%xmoney_top_<rank>_name%` and `_balance`.
- A developer API for payments with a reason in the ledger, the top list and history, and two events.
