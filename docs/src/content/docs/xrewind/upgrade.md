---
title: Upgrading from 1.x
description: "Move from xRewind 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every command, parameter, permission and setting works as it did, and your history is where it was.

## What the first start changes

- **Your history stays as it is.** 2.0 reads and writes `history.db` (or your MySQL tables) exactly as 1.x does: the same tables, columns, times and schema version, so nothing is converted and nothing is copied. Containers' items and signs' text are kept in 1.x's format on Paper and Folia.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. `log`, `rollback`, `purge` and `storage` are where they were.
- **Messages move to `lang/`.** 2.0 keeps xRewind's messages in one file per language (`lang/en.yml`, `lang/de.yml`, ...). Each message you changed in 1.x's `config.yml` is copied into `lang/en.yml` under its new key (`messages.lookup.header` is `lookup.header`; the errors are `core.command.*`). Messages you never changed get 2.0's, which are 1.x's English.
- **The language.** If you had changed any message, xRewind's `messages.language` is set to `en`, so staff see your words, as in 1.x. Set it to `auto` (or `shared`) to give each their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xRewind's own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.

## Going back to 1.1.1

1. Stop the server.
2. Put the 1.1.1 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

Your history needs nothing: 1.1.1 reads the database as 2.0 left it, every change logged with 2.0 included. If you skip step 3, 1.1.1 still starts and reads 2.0's file as a newer one, keeping every setting, with its own default messages. 1.1.1 runs on Paper and Folia 1.21.11 and newer only, as it always did; history 2.0 logged on Spigot or Fabric keeps its blocks, but 1.1.1 can't put back the items in it. A rollback a restart stopped under 2.0 isn't carried on by 1.1.1: run it again.

## For developers

The API changed: worlds and players are platform-neutral, and the events are xRewind's own (and Bukkit events beside them). See [Developer API](/xrewind/developer-api/#from-1x).
