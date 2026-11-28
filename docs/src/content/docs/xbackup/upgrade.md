---
title: Upgrading from 1.x
description: "Move from xBackup 1.x to 2.0: what the first start changes, and how to go back."
sidebar:
  order: 7
---

Replace the jar and restart. Nothing else is needed: every backup, command, permission, remote and setting works as it did.

## What the first start changes

- **Your backups stay as they are.** 2.0 reads and writes 1.x's repository format byte for byte: packs, index, snapshots, pins and the remotes' copies. Nothing is converted, and there's no new full backup: the next one stores only what changed.
- **`config.yml` is carried forward** to 2.0's, and the old file is kept beside it as `config.yml.bak-v1`. Every setting keeps its place and meaning: `command`, `repository`, `schedule`, `sources`, `exclude`, `retention`, `performance`, `verify` and `remotes`.
- **Passwords stay out of `config.yml`.** A remote's `password` or `client-secret` still in `config.yml` moves to `secrets.yml`, as in 1.x; the copy aside has none either.
- **Messages move to `lang/`.** 2.0 keeps xBackup's messages in one file per language (`lang/en.yml`, `lang/de.yml`, ...). Each message you changed in 1.x's `config.yml` is copied into `lang/en.yml` (`messages.error.*` become `core.command.*`; `messages.broadcast-start` and `broadcast-done` become `broadcast.start` and `broadcast.done`), and the console says how many.
- **The broadcasts** are switched by `broadcasts.start` and `broadcasts.done`. One you'd given text in 1.x is switched on, so it's still said.
- **The language.** If you had changed any message, `messages.language` is set to `en`, so every player sees your words, as in 1.x. Set it to `shared` to give each player their own language.
- **The theme moves into `messages.theme`.** A colour you'd changed stays xBackup's own; the rest become `shared`, the look every Xena plugin takes from `plugins/Xena/config.yml`.
- **The update checker** is `updates` now: `update-checker.enabled` becomes `updates.check`.
- **The command-line restore** is the same commands, with the jar's new name (`java -jar plugins/xBackup.jar`), and needs Java 8 or newer.

A restore staged with 1.x and not yet put in place is put in place by 2.0 on its first start.

## Going back to 1.0.1

1. Stop the server.
2. Put the 1.0.1 jar back in place of 2.0's.
3. Rename 2.0's `config.yml` to `config.yml.v2` (to come back to later), and `config.yml.bak-v1` to `config.yml`.

The repository needs nothing: 1.0.1 reads every backup 2.0 made, and restores them. If you skip step 3, 1.0.1 still starts and reads 2.0's file as a newer one, keeping every setting, with its own default messages. 2.0's `lang/` folder is left alone; 1.0.1 doesn't read it. 1.0.1 runs on Paper and Folia only, on Java 21, as it always did.

## For developers

The API moved to `XBackup.api()`, with plugins by name and paths as strings: see [Developer API](/xbackup/developer-api/#from-1x).
