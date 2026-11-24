---
title: Upgrading from 2.x
description: "Move from xUtilities 2.x to 3.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every command, alias, permission, link command and `/back` works as it did.

## What the first start changes

- **`config.yml` is carried forward** to 3.0's, and the old file is kept beside it as `config.yml.bak-v2` (`config.yml.bak-v1` from 1.x). Every setting keeps its place, so `commands.fly.aliases` is still `commands.fly.aliases`.
- **Messages move to `lang/`.** 3.0 keeps every message in one file per language (`lang/en.yml`, `lang/de.yml`, ...). Each message you changed in 2.x's `config.yml` is copied into `lang/en.yml` under its new key (`commands.heal.message-self` is `heal.self`, the errors are `core.command.*`), and the console says how many. Messages you never changed get 3.0's.
- **The language.** If you had changed any message, xUtilities' `messages.language` is set to `en`, so every player sees your words, as in 2.x. Set it to `auto` (or `shared`, the family's choice) to give each player their own language; then translate your edits into the other files too, or players reading them get the shipped text.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xUtilities' own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **Gone:** `update-checker.message` (the update checker comes back with the next alpha) and `messages.error.disabled` (1.x's).

## Going back to 2.1.0

1. Stop the server.
2. Put the 2.1.0 jar back in place of 3.0's.
3. Rename 3.0's `config.yml` to `config.yml.v3` (to come back to later), and `config.yml.bak-v2` to `config.yml`.

If you skip step 3, 2.1.0 still starts and says the config is from a newer version: it reads it without changing it, so your commands' settings and link commands are kept, and it uses its own default messages and theme. 3.0's `lang/` folder is left alone; 2.1.0 doesn't read it.

## For developers

The API changed: players are by UUID, places are the core's, and the events have new names. See [Developer API](/xutilities/developer-api/#from-2x).
