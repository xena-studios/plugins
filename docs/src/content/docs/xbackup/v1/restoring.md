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

Plugin jars are loaded before xBackup can put files back, so a restored jar takes effect from the start after; xBackup says so when you stage one. To restore jars at once, use the command line with the server stopped.

If putting a restore in place fails part way (a file the system keeps locked, a full disk), xBackup moves everything back as it was, the server starts with what it had, and the console says why. That restore isn't tried again by surprise on a later start: its journal is kept as `restore/failed`, and you stage it again once the cause is fixed. If even moving things back fails, xBackup stops the server from starting, rather than let it load half a world, and the console lists where everything is.

A world kept on another disk behind a link is restored where the link leads, with what was there set aside beside it on that disk.

## From the command line

With the server stopped, or when it won't start:

```sh
cd /path/to/server
java -jar plugins/xbackup-1.0.0.jar list
java -jar plugins/xbackup-1.0.0.jar restore latest --dry-run
java -jar plugins/xbackup-1.0.0.jar restore latest
java -jar plugins/xbackup-1.0.0.jar restore 3f9a1c2e world plugins/xRewind
```

It shows the same summary as in game and asks before changing anything (`--yes` skips the question). It refuses while the server is running: xBackup keeps `plugins/xBackup/running.lock` locked from the moment the server starts until its process exits, and each loaded world keeps its `session.lock` locked (looked for in the folders being restored, a few folders deep in the server folder, and in `bukkit.yml`'s `world-container`). The restore holds `running.lock` itself until it's done, so a server started meanwhile stops straight away, before loading any world, and says why; it checks the worlds again just before it swaps anything. `--to <folder>` restores into another folder instead, which must be new or empty, and is safe with the server running, for picking out one file by hand.

A restore only ever writes inside its folder. A link in the backup that points outside the server folder (directly, or through a link already on the disk) isn't restored, in case the backup was tampered with; `--allow-links-outside` restores it, for links you made yourself. On Windows, a name Windows would read as another file's (a trailing dot or space, a device name such as `NUL` or `COM1`, a short name such as `PROGRA~1`) is refused too.

It needs only Java 21, not the server, and reads where the repository and remotes are from `plugins/xBackup/restore-settings.properties`, which the plugin keeps up to date, and the remotes' passwords from `secrets.yml`. `--repo <folder>` points it at a repository anywhere else, such as a copy on another machine.

## From a remote

When the server's disk is gone, restore from a [remote](/xbackup/v1/remotes/): put the plugin and its `restore-settings.properties` back (or a fresh server with the same `config.yml` and `secrets.yml`, started once), then

```sh
java -jar plugins/xbackup-1.0.0.jar list --remote offsite
java -jar plugins/xbackup-1.0.0.jar restore latest --remote offsite
```

It downloads only what the restore needs, into `plugins/xBackup/temp/`, restores from there, and removes it.

## Checking backups

`/backup verify` reads back a share of the stored data, `/backup verify full` all of it, each piece checked against its hash. If the index is damaged, `/backup repair` rebuilds it from the data itself.
