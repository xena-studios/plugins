---
title: Upgrading from 1.x
description: "Move from xPlots 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every command, permission and setting works as it did, and every field, plot, rent, member, sign and saved block is where it was.

## What the first start changes

- **Your plots stay as they are.** 2.0 reads and writes `plots.db` (or your MySQL tables) exactly as 1.x does: the same tables, columns and schema version, and plots' saved blocks in 1.x's own format, so nothing is converted and nothing is copied. 2.0's one addition, renewals, is a table of its own beside them, which 1.x never reads. A field's own settings are kept as 1.x kept them; 2.0's `auto-renew` among them is one 1.x skips.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. `plots`, `currency` (with `vault` as it was), `max-plots`, the signs' material and placement, the menus' materials, `teleport-warmup`, `reset-ms-per-tick` and `storage` are where they were.
- **Texts move to `lang/`.** 2.0 keeps every message in one file per language (`lang/en.yml`, `lang/de.yml`, ...), and with them the signs' lines and the menus' titles, names and lore. Each one you changed in 1.x's `config.yml` is copied into `lang/en.yml` under its new key (`messages.rented` is `rented`, `signs.free` stays `signs.free` with its four lines joined by `<newline>`, `menus.rent.confirm.name` stays where it was; the errors are `core.command.*`). Texts you never changed get 2.0's, which are 1.x's English.
- **The language.** If you had changed any text, xPlots' `messages.language` is set to `en`, so players see your words, as in 1.x. Set it to `auto` (or `shared`) to give each player their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xPlots' own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.
- **Staff replies are messages too.** 1.x's `/xplots` replies were English only; 2.0's are in `lang/` under `staff.*`.

## Going back to 1.0.2

1. Stop the server.
2. Put the 1.0.2 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

Your plots need nothing: 1.0.2 reads the database as 2.0 left it, every rent, member and sign move 2.0 made included, and leaves 2.0's renewals table alone. Plots renewed by 2.0 keep the time they were paid for; 1.0.2 just doesn't renew them again. If you skip step 3, 1.0.2 still starts and reads 2.0's file as a newer one, keeping every setting, with its own default texts (an `economy` currency becomes 1.x's `vault`, the same thing). 1.0.2 runs on Paper 1.21.11 and newer only, as it always did.

## For developers

1.x had no API; 2.0's is new: see [Developer API](/xplots/developer-api/).
