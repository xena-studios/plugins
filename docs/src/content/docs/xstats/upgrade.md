---
title: Upgrading from 1.x
description: "Move from xStats 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 9
---

Replace the jar and restart. Nothing else is needed: every stat, board, command, permission and setting works as it did.

## What the first start changes

- **Your stats stay as they are.** 2.0 reads and writes `stats.db` (or your MySQL tables) exactly as 1.x did: the same tables, columns and schema version (2). Nothing is converted, and the import doesn't run again: it ran on 1.x's first start.
- **Boards in the world** are read from the same table and appear where they were.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. Every setting keeps its place: `commands`, `stats`, `collecting`, `leaderboards`, `periods`, `web`, `server-name`, `storage` and `network`.
- **Messages move to `lang/`.** Each message you changed in 1.x's `config.yml` is copied into `lang/en.yml` under its new key (`messages.profile-header` is `profile-header`; the boards' `leaderboards.boards.title`, `line` and `empty` are `boards.*`, and `periods.names.*` are `periods.*`; the errors are `core.command.*`, and `messages.page-footer` is `core.pages.footer`), and the console says how many. Messages you never changed get 2.0's, which are 1.x's English.
- **The language.** If you had changed any message, xStats' `messages.language` is set to `en`, so every player sees your words, as in 1.x. Set it to `auto` (or `shared`) to give each player their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xStats' own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.
- **New sections**: `integrations` (the family's stats, on by default) and `proxy` (xStats on Velocity).

## Going back to 1.0.1

1. Stop the server.
2. Put the 1.0.1 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

Your stats need nothing: 1.0.1 reads the database as 2.0 left it, every save 2.0 made included, still at schema 2. The family's stats (`xvote_votes` and the rest) keep their rows; 1.0.1 doesn't show them, and 2.0 shows them again if you come back. If you skip step 3, 1.0.1 still starts and reads 2.0's file as a newer one, keeping every setting, with its own default messages. 2.0's `lang/` folder is left alone. 1.0.1 runs on Paper and Folia only, and not on the proxy: take xStats off the proxy too.

## For developers

The API changed: players are by UUID as `PlayerRef`s, the reads are named for what they return, and the events are xStats' own (and Bukkit events beside them). See [Developer API](/xstats/developer-api/#from-1x).
