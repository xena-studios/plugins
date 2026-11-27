---
title: Upgrading from 1.x
description: "Move from xBans 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every punishment, history, IP record, command, flag, permission and setting works as it did.

## What the first start changes

- **Your punishments stay as they are.** 2.0 reads and writes `punishments.db` (or your MySQL tables) exactly as 1.x did: the same tables, columns and schema version, hashed IPs and `ip-salt.txt` included. Nothing is converted.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. Every setting keeps its place: `commands`, `punishing`, `mutes`, `warnings`, `templates`, `staff-limits`, `ips`, `scopes`, `lists`, `storage` and `network` are where they were, and `screens.appeal-link` too.
- **Messages move to `lang/`.** 2.0 keeps xBans' messages in one file per language (`lang/en.yml`, `lang/de.yml`, ...), ban screens and broadcasts included. Each message you changed in 1.x's `config.yml` is copied into `lang/en.yml` under its new key (`messages.not-banned` is `not-banned`; `screens.*` and `broadcasts.*` keep theirs; the errors are `core.command.*`), and the console says how many. Messages you never changed get 2.0's, which are 1.x's English.
- **The language.** If you had changed any message, xBans' `messages.language` is set to `en`, so every player sees your words, as in 1.x. Set it to `auto` (or `shared`) to give each player their own language, ban screens included.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xBans' own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.
- **On a network**, install xBans on the proxy too, to turn banned players away there: see [Networks](/xbans/networks/). The servers work as before without it.

## Going back to 1.0.1

1. Stop the server.
2. Put the 1.0.1 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

Your punishments need nothing: 1.0.1 reads the database as 2.0 left it, every punishment made with 2.0 included. If you skip step 3, 1.0.1 still starts and reads 2.0's file as a newer one (it says so once), keeping every setting, with its own default messages. 2.0's `lang/` folder is left alone; 1.0.1 doesn't read it. 1.0.1 runs on Paper and Folia only, as it always did, and not on the proxy: take xBans off the proxy too.

## For developers

The API changed: players are by UUID as `PlayerRef`s, results are `PunishResult`s, and the events are xBans' own (and Bukkit events beside them). See [Developer API](/xbans/developer-api/#from-1x).
