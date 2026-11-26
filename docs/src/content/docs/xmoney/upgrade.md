---
title: Upgrading from 1.x
description: "Move from xMoney 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every balance, ledger line, command, alias, permission and setting works as it did.

## What the first start changes

- **Your balances and ledger stay as they are.** 2.0 reads and writes `money.db` (or your MySQL tables) exactly as 1.x did: the same tables, columns and schema version. Nothing is converted.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. Every setting keeps its place: `currency`, `starting-balance`, `max-balance`, `commands`, `ledger`, `storage`, `network` and `network-wait-ms` are where they were.
- **Messages move to `lang/`.** 2.0 keeps xMoney's messages in one file per language (`lang/en.yml`, `lang/de.yml`, ...). Each message you changed in 1.x's `config.yml` is copied into `lang/en.yml` under its new key (`messages.pay.sent` is `pay.sent`, the errors are `core.command.*`), and the console says how many. Messages you never changed get 2.0's, which are 1.x's English.
- **The language.** If you had changed any message, xMoney's `messages.language` is set to `en`, so every player sees your words, as in 1.x. Set it to `auto` (or `shared`) to give each player their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xMoney's own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.

## Going back to 1.0.1

1. Stop the server.
2. Put the 1.0.1 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

Your balances and ledger need nothing: 1.0.1 reads the database as 2.0 left it, every change 2.0 made included. If you skip step 3, 1.0.1 still starts and reads 2.0's file as a newer one (it says so once), keeping every setting, with its own default messages. 2.0's `lang/` folder is left alone; 1.0.1 doesn't read it.

## For developers

The API changed: players are by UUID, amounts are exact `Amount`s, and the events are xMoney's own (and Bukkit events beside them). See [Developer API](/xmoney/developer-api/#from-1x).
