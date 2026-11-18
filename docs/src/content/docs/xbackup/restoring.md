---
title: Restoring
description: Getting a world, a plugin's data or a whole server back from an xBackup backup.
sidebar:
  order: 2
---

A restore always shows what it will change first, checks every file against the hash it was saved with, and never writes over a running world. What it replaces is kept, in `plugins/xBackup/restore/replaced/<time>/`, until you delete it.

## In game

```
/backup list
/backup restore 3f9a1c2e world
```

The first command lists backups; click one to start a restore. The second shows what restoring `world` from that backup would change: how many files and chunks, what comes back and what's set aside. Click **[Restore it]** (or add `confirm`) and the files are written and checked into `plugins/xBackup/restore/staged/`. Nothing in use changes yet.

On the **next start**, before any world loads, xBackup moves each restored path's current files aside and puts the staged ones in place. It takes milliseconds, and the console says what it did. `/backup restore cancel` calls it off before then.

Leave out the path to restore everything, or give several: `world world_nether plugins/xRewind`. A single file works too: `server.properties`. A restored SQLite database's old `-wal` and `-shm` files are set aside with it, so SQLite never replays them over the restored copy.

xBackup never restores over itself: its own folder, its jar and the repository are left out.

## From the command line

With the server stopped, or when it won't start:

```sh
cd /path/to/server
java -jar plugins/xbackup-1.0.0.jar list
java -jar plugins/xbackup-1.0.0.jar restore latest --dry-run
java -jar plugins/xbackup-1.0.0.jar restore latest
java -jar plugins/xbackup-1.0.0.jar restore 3f9a1c2e world plugins/xRewind
```

It shows the same summary as in game and asks before changing anything (`--yes` skips the question). It refuses while the server is running: it checks each world's `session.lock`, which the server holds. `--to <folder>` restores into another folder instead, which is safe with the server running, for picking out one file by hand.

It needs only Java 21, not the server, and reads where the repository and remotes are from `plugins/xBackup/restore-settings.properties`, which the plugin keeps up to date. `--repo <folder>` points it at a repository anywhere else, such as a copy on another machine.

## From a remote

When the server's disk is gone, restore from a [remote](/xbackup/remotes/): put the plugin and its `restore-settings.properties` back (or a fresh server and the same config), then

```sh
java -jar plugins/xbackup-1.0.0.jar list --remote offsite
java -jar plugins/xbackup-1.0.0.jar restore latest --remote offsite
```

It downloads only what the restore needs, into `plugins/xBackup/temp/`, restores from there, and removes it.

## Checking backups

`/backup verify` reads back a share of the stored data, `/backup verify full` all of it, each piece checked against its hash. If the index is damaged, `/backup repair` rebuilds it from the data itself.
