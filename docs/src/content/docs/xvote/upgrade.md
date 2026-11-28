---
title: Upgrading from 1.x
description: "Move from xVote 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every vote, total, key, token, command, permission and setting works as it did.

## What the first start changes

- **Your votes stay as they are.** 2.0 reads and writes `votes.db` (or your MySQL tables) exactly as 1.x did: the same tables, columns and schema version (2). Nothing is converted. Votes 1.x hadn't paid yet are paid as their players join.
- **Your keys and tokens** in `rsa/` and `tokens.yml` are used as they are, so no list changes.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. Every setting keeps its place: `receiver`, `sites`, `rewards` (their messages included), `triggers`, `party`, `reminders`, `counting`, `storage` and `network`.
- **Messages move to `lang/`.** Each message you changed in 1.x's `config.yml` is copied into `lang/en.yml` under its new key (`messages.list.header` is `list.header`; `party.started`, `party.progress` and `reminders.message` keep theirs; the errors are `core.command.*`, and `messages.top.footer` is `core.pages.footer`), and the console says how many. Messages you never changed get 2.0's, which are 1.x's English.
- **The language.** If you had changed any message, xVote's `messages.language` is set to `en`, so every player sees your words, as in 1.x. Set it to `auto` (or `shared`) to give each player their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xVote's own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.
- **Money** is paid through xMoney when it's installed, otherwise through Vault, as before.
- **On a network**, you can now run the receiver on the proxy: see [Networks](/xvote/networks/). The servers work as before without it.

## Going back to 1.0.1

1. Stop the server.
2. Put the 1.0.1 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

Your votes need nothing: 1.0.1 reads the database as 2.0 left it, every vote 2.0 received included. Reward items 2.0 held for `/vote claim` on Paper are in 1.x's format; on Spigot, which 1.x never ran on, they're Bukkit's, which 1.x can't read. If you skip step 3, 1.0.1 still starts and reads 2.0's file as a newer one (it says so once), keeping every setting, with its own default messages. 2.0's `lang/` folder is left alone. 1.0.1 runs on Paper and Folia only, and not on the proxy: take xVote off the proxy too, and turn a server's receiver back on.

## For developers

The API changed: players are by UUID as `PlayerRef`s, and the events are xVote's own (and Bukkit events beside them). See [Developer API](/xvote/developer-api/#from-1x).
