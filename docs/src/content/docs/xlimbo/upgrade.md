---
title: Upgrading from 2.x
description: "Move from xLimbo 2.x to 3.0: what the first start changes, and how to go back."
sidebar:
  order: 8
---

Replace the jar and restart. Nothing else is needed: the world, `/join`, its aliases and permission, the queue, server-check and every protection work as they did. 3.0 needs Paper 1.21.11 or newer and Java 21, as 2.x did.

## What the first start changes

- **`config.yml` is carried forward** to 3.0's, and the old file is kept beside it as `config.yml.bak-v2` (`config.yml.bak-v1` from 1.x). Every setting keeps its place, so `world.floor-y` is still `world.floor-y`.
- **The world is kept.** 3.0 marks and reads its world as 2.x did (`xlimbo.dat`), so a saved world (`auto-save: true`) is taken over as it is, builds and all; a temporary one is made fresh, as on every start. From 1.x, its unmarked world is taken over too.
- **Messages move to `lang/`.** Each message you changed in 2.x's `config.yml` is copied into `lang/en.yml` under its new key, and the console says how many:

  | 2.x | 3.0 (`lang/`) |
  | --- | --- |
  | `join.custom-join-message` | `join.welcome` |
  | `auto-join.message` | `auto-join.sending` |
  | `server-check.message-up` | `server-check.up` |
  | `queue.action-bar` | `queue.action-bar` |
  | `join-command.message-sending` | `join-command.sending` |
  | `join-command.message-cooldown` | `join-command.cooldown` |
  | `action-bar.message` | `action-bar.hint` |
  | `messages.error.*` | `core.command.*` |

  Messages you never changed get 3.0's.
- **The language.** If you had changed any message, xLimbo's `messages.language` is set to `en`, so every player sees your words, as in 2.x. Set it to `auto` (or `shared`, the family's choice) to give each player their own language; then translate your edits into the other files too.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xLimbo's own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`. Its message is `core.update.available` in `lang/`.
- **New:** `send-back` (`via: proxy`, so nothing changes until you choose `teleport`), and `/xlimbo list`, `send` and `back`.

## Going back to 2.1.0

1. Stop the server.
2. Put the 2.1.0 jar back in place of 3.0's.
3. Rename 3.0's `config.yml` to `config.yml.v3` (to come back to later), and `config.yml.bak-v2` to `config.yml`.

If you skip step 3, 2.1.0 still starts and says the config is from a newer version: it reads it without changing it, so every setting is kept (`send-back`, which it doesn't know, is ignored), and it uses its own default messages and theme. Its world is the same world: 2.1.0 reads 3.0's marker. 3.0's `lang/` folder is left alone; 2.1.0 doesn't read it.

## For developers

The API changed: players are `PlayerRef`s (by UUID), it's `XLimbo.api()`, and the events have new names, on xLimbo's own bus as well as Bukkit's. See [Developer API](/xlimbo/developer-api/#from-2x).
