---
title: Performance
description: What xBackup costs a running server, and how it stays that way.
sidebar:
  order: 6
---

## The pause

The only work on the server's own threads is the pause: autosave off, players saved, and every world written to disk. Then autosave goes back on, from a `finally`, with a watchdog in case a backup ever hangs. How the worlds are written depends on the platform:

| Platform | Autosave off | Written with |
| --- | --- | --- |
| Paper | `World#setAutoSave(false)` | `World#save(true)`, which writes each changed chunk before it returns. |
| Folia | the same | `World#save(true)` once per region, on that region's thread, all in parallel: it only saves its own region. |
| Spigot, CraftBukkit, Paper before `save(true)` | the same | `World#save()`, which hands chunks to the server's writer thread; xBackup then waits, off the server's threads, until the region files stop changing. |
| Fabric | each level's `noSave`, as `/save-off` does | `saveAllChunks` with flush and force, as `/save-all flush` does, which waits for the region writers. |

In the boot tests the server's thread spent 10 to 80 ms per backup on Paper 1.21.11, Folia 26.3, Spigot 1.8.8 and Fabric 1.20.1 to 26.3. It grows with how many chunks changed since the last autosave, not with the size of the world. `/backup status` shows the real numbers for each backup.

While autosave is off, the server still runs normally; chunks players leave are held in memory instead of written, and written as soon as it's back on. Nothing is lost. However a backup ends (finished, failed, given up by the watchdog, or the plugin disabled), autosave goes back on for exactly the worlds it turned off.

With nobody online, Paper pauses the server after `pause-when-empty-seconds` (60 by default). Backups still run then: xBackup's steps on the main thread go through Paper's scheduler, which keeps running while the server is paused, and the backup doesn't wake the server up. Folia doesn't pause when empty. On Fabric, a chunk the server unloads while saving is off can still be written; the second read reads each chunk whole or reads it again, so the backup stays one moment.

## Reading only what changed

Each backup reads the worlds twice, both off the server's threads. The first read runs with the server saving as usual and stores whatever changed since the last backup. The second, during the pause, reads only what changed since the first. So autosave is off for a moment even after an hour of building, and even the very first backup of a big world.

What counts as changed is cheap to tell: each region file's header records where every chunk sits and when it was written, so an unchanged chunk is recognised from 8 bytes and reused without being read. Those times are whole seconds, so a chunk stamped in the same second as the last read is read again, to be sure. Other files are recognised by size and time.

## Storage

Each piece of data is named by its SHA-256 and stored once, however many backups use it. Region chunks are stored as Minecraft compressed them; other files are deflated, and big ones are cut at content-defined points, so a change near the start of a large file doesn't make the rest look new.

## At startup

xBackup reads its config and registers its commands as the server starts, and nothing else: the repository isn't opened until the first command or backup needs it, and the schedule is set from the last backup's time, kept in `state.properties`. On Paper 26.3 it takes about 90 ms of the server's start, and holds about 1.3 MB of class metadata.

## Threads and timers

All backup work runs on one low-priority thread, one job at a time, with optional disk and upload limits. The only timer is the schedule: one task set for the next backup, and set again after it. Checking older data and giving back space ride along after a scheduled backup when they're due. A join listener runs only with `skip-when-idle`.

Between backups, xBackup holds a list of backups in memory and nothing else. During one, the index of stored pieces takes about 65 bytes per piece: roughly 65 MB for a world of a million chunks.
