---
title: Commands
description: xBackup's commands and permissions.
sidebar:
  order: 4
---

Every `/backup` subcommand has its own permission, `xbackup.<subcommand>`, which operators have. Give a helper `xbackup.list` and `xbackup.status` and they can look without changing anything.

| Command | What it does |
| --- | --- |
| `/backup now [reason]` | Backs up now. The reason shows in the list. |
| `/backup list [page]` | Backups, newest first: id, age, reason, size and what each added. Click one to restore it. |
| `/backup status` | The last backup, how long the server paused for it, the next one, the repository's size, each remote, and anything wrong. |
| `/backup verify [full]` | Reads back a share of the stored data (all of it with `full`) and checks it against its hashes. |
| `/backup restore <id\|latest> [path...]` | Shows what restoring would change. Add `confirm` to stage it for the next start. See [Restoring](/xbackup/restoring/). |
| `/backup restore cancel` | Calls off a restore waiting for the next start. |
| `/backup prune` | Removes backups retention doesn't keep, and gives back their space now instead of at the daily clean-up. |
| `/backup pin <id>`, `/backup unpin <id>` | Keeps a backup forever, or lets retention remove it again. |
| `/backup forget <id> confirm` | Removes one backup now. |
| `/backup copy <remote>` | Copies the backups to a remote now. |
| `/backup copy <remote> tidy [confirm]` | Lists the backups only the remote has; with `confirm`, removes them and the data no backup here needs. See [what a copy removes](/xbackup/remotes/#what-a-copy-removes). |
| `/backup link <remote> [code]` | Links a Google Drive, OneDrive or Dropbox remote: see [Remotes](/xbackup/remotes/#cloud-drives). |
| `/backup repair` | Rebuilds the index from the stored data, if `verify` says the index is damaged. |
| `/xbackup reload`, `/xbackup info` | Reload the config, or show the version and settings. Need `xbackup.admin`. |

A backup's id is the 8 characters `/backup list` shows; `latest` means the newest one. Paths are relative to the server folder: `world`, `world_nether`, `plugins/xRewind`, `server.properties`.

`/backup` also answers to `/backups`, and `/xbackup` to `/xb`.

## Permissions

| Permission | Default | What it allows |
| --- | --- | --- |
| `xbackup.now` | Operators | `/backup now` |
| `xbackup.list` | Operators | `/backup list` |
| `xbackup.status` | Operators | `/backup status` |
| `xbackup.verify` | Operators | `/backup verify` |
| `xbackup.restore` | Operators | `/backup restore` |
| `xbackup.prune` | Operators | `/backup prune` |
| `xbackup.pin`, `xbackup.unpin` | Operators | Pinning and unpinning |
| `xbackup.forget` | Operators | `/backup forget` |
| `xbackup.copy` | Operators | `/backup copy` |
| `xbackup.link` | Operators | `/backup link` |
| `xbackup.repair` | Operators | `/backup repair` |
| `xbackup.admin` | Operators | `/xbackup reload` and `/xbackup info` |

## From the command line

The same jar restores with the server stopped, and lists and checks backups:

```sh
java -jar plugins/xbackup-1.0.0.jar list
java -jar plugins/xbackup-1.0.0.jar restore latest --dry-run
java -jar plugins/xbackup-1.0.0.jar verify --full
```

See [Restoring](/xbackup/restoring/#from-the-command-line).
