# xMoney changelog

## 1.0.0

The first release. Remove Vault or VaultUnlocked, drop the jar in and start the server: xMoney is your economy and your Vault, with balances kept in SQLite in its own folder and nothing to set up. Coming from another economy plugin? Run `/xmoney import essentials`, `cmi` or `vault` before removing it.

### Vault, built in

- Plugins that use Vault or VaultUnlocked find xMoney without a Vault jar, on Paper and Folia. Checked with ChestShop, EconomyShopGUI and LuckPerms.
- If Vault or VaultUnlocked is still installed, Paper loads xMoney and refuses the other jar; xMoney's warning names the jar to delete.
- Vault's permission and chat APIs are included, so LuckPerms and other permissions plugins hook them as before. Without one, Vault's permission checks answer from the server's own permissions, as Vault did.

### Money

- `/balance`, `/pay` (asks to confirm above `confirm-above`), `/baltop`, and `/eco give|take|set|reset|history` for staff, for players online or offline.
- Every change is in the ledger: when, how much, the balance after, and why, such as "ChestShop" or "paid Alex". `/eco history` shows it. Lines older than a year are deleted; balances never depend on them.
- Amounts are whole numbers underneath, rounded to your currency's decimals, so nothing drifts and odd amounts from other plugins (NaN, negative, huge) are refused.
- The currency's name, symbol, decimals and format are in the config, and other plugins show money that way too.
- Every balance is in memory and saved as it changes, so Vault calls never wait on the disk, from any thread.

### Also

- Imports from EssentialsX, CMI and any economy registered with Vault, each with a dry run first.
- Networks: servers sharing one MySQL or MariaDB database share balances, and can never spend the same money twice.
- PlaceholderAPI: `%xmoney_balance%` (and `_raw`, `_short`), and `%xmoney_top_<rank>_name%` and `_balance`.
- A developer API for payments with a reason in the ledger, the top list and history, and two events.
