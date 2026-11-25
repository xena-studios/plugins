---
title: Upgrading from 3.x
description: "Move from zChat 3.x to 4.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every format, command, alias, permission and filter word works as it did.

## What the first start changes

- **`config.yml` is carried forward** to 4.0's, and the old file is kept beside it as `config.yml.bak-v2` (`config.yml.bak-v1` from 2.x). Every setting keeps its place: `formatting.groups`, `filter.words`, `cooldown.seconds` and the rest are where they were. A zChat 1.x file, from a different plugin, is set aside as `config.yml.old`, as 3.x did.
- **Messages move to `lang/`.** 4.0 keeps zChat's messages in one file per language (`lang/en.yml`, `lang/de.yml`, ...). Each message you changed in 3.x's `config.yml` is copied into `lang/en.yml` under its new key (`cooldown.message` is `cooldown.wait`, `filter.command.message-added` is `filter.added`, the errors are `core.command.*`), and the console says how many. Messages you never changed get 4.0's. **Chat formats stay in `config.yml`**: they're your server's look.
- **The language.** If you had changed any message, zChat's `messages.language` is set to `en`, so every player sees your words, as in 3.x. Set it to `auto` (or `shared`) to give each player their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays zChat's own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.
- **Remembered choices.** A player's `/togglechat` and `/socialspy` choice that 3.x kept on the player is brought into `players.records` the first time they join 4.0 (Bukkit 1.14 and newer).
- **"No player named ... is online"** is now the family's shared "No player called ... is online", unless you'd edited it.

## Going back to 3.2

1. Stop the server.
2. Put the 3.2 jar back in place of 4.0's.
3. Rename 4.0's `config.yml` to `config.yml.v4` (to come back to later), and `config.yml.bak-v2` to `config.yml`.

If you skip step 3, 3.2 still starts and reads 4.0's file as a newer one, keeping its formats, filter and commands, with its own default messages. 4.0's `lang/` folder and `players.records` are left alone; 3.2 doesn't read them, and its own remembered choices are still on the players.

## For developers

The API changed: players are by UUID, text is markup, and the events are zChat's own (and Bukkit events beside them). See [Developer API](/zchat/developer-api/#from-3x).
