---
title: Switching
description: Move balances to xMoney from EssentialsX, CMI or any economy plugin, and take out Vault.
sidebar:
  order: 2
---

xMoney is an economy plugin and Vault in one, so switching means removing two things: your old economy plugin, and Vault. Bring the balances first.

## Bring the balances

1. Install xMoney **next to** your old economy plugin, and remove Vault or VaultUnlocked (xMoney is Vault now). Start the server.
2. See what an import would do. Nothing changes yet:

   ```
   /xmoney import essentials
   ```

   It says how many balances it found, their total, and how many it can't import (not a number, or more than `max-balance`). A balance below zero comes across as 0. Amounts keep up to four decimal places, whatever your currency's `decimals`.
3. Import, with the link in that message or:

   ```
   /xmoney import essentials confirm
   ```

   Each player's balance in xMoney is set to the one they had. Every change is in the ledger as "imported from EssentialsX", so `/eco history` still shows what they had before. Names xMoney already knows are kept.
4. Remove the old economy plugin.

Running an import again sets the balances again, to the old plugin's, undoing anything players did in xMoney since. So if any of the accounts have changed in xMoney since (a payment, `/eco`, a shop), the dry run says how many, and the import needs `overwrite` too:

```
/xmoney import essentials confirm overwrite
```

On a network, run the import on one server. It's set a hundred balances at a time with payments between, waits rather than drops anything if the database goes away, and the other servers read every balance again once it's done. Shop purchases on any server may be refused while it runs, so pick a quiet time.

## Where it reads from

| Command | Reads |
| --- | --- |
| `/xmoney import essentials` | EssentialsX's `plugins/Essentials/userdata/*.yml`. Accounts EssentialsX made for other plugins (a town's bank) come across as accounts that aren't players'. |
| `/xmoney import cmi` | CMI's SQLite database, `plugins/CMI/cmi.sqlite.db`, read-only. |
| `/xmoney import vault` | Whatever other economy plugin is registered with Vault, while it's still installed. This covers every other economy plugin, and CMI or EssentialsX on MySQL. Economy plugins expect to be asked on the main thread, so balances are read there, a few milliseconds each tick, so the server doesn't stall. |

Only players who have joined this server are imported from a plain Vault economy, since Vault can't list accounts. An economy on VaultUnlocked's API lists its own.

## EssentialsX

EssentialsX itself can stay for its other commands. Beside xMoney, `/balance`, `/pay`, `/baltop` and `/eco` are xMoney's (EssentialsX's are still there as `/essentials:balance` and so on); to be sure players never reach EssentialsX's economy, list those commands under `disabled-commands` in EssentialsX's config.

## CMI

CMI has economy commands of its own. Once you've imported, turn CMI's economy off in its settings, or its money commands off in its command config, so players only use xMoney's.

## Vault2.0 and other "no Vault needed" plugins

They take Vault's name, so they can't run beside xMoney either. Import with `/xmoney import vault` while it's installed, then remove it.

## What changes for other plugins

Nothing. Shops, jobs, ranks and vote plugins find xMoney through Vault, on Paper and Folia. Checked on real servers with ChestShop, EconomyShopGUI and LuckPerms.

One thing Vault had that xMoney doesn't: Vault's own commands, `/vault-info` and `/vault-convert`. The import above does what `/vault-convert` did.
