---
title: Performance
description: What xBackup costs a running server, and how it stays that way.
sidebar:
  order: 6
---

## The pause

The only work on the server's own threads is the pause: autosave off, players saved, and every world written to disk with `World#save(true)`, which writes each changed chunk before it returns. On Folia that call only saves its own region, so it runs once per region, on that region's thread, all in parallel. Then autosave goes back on, from a `finally`, with a watchdog in case a backup ever hangs.

Measured on Paper with about 50 changed chunks, the server's thread spends 15 to 45 ms; on Folia the longest single region's save was 15 to 60 ms. It grows with how many chunks changed since the last autosave, not with the size of the world. `/backup status` shows the real numbers for each backup.

While autosave is off, the server still runs normally; chunks players leave are held in memory instead of written, and written as soon as it's back on. Nothing is lost.

## Reading only what changed

Each backup reads the worlds twice, both off the server's threads. The first read runs with the server saving as usual and stores whatever changed since the last backup. The second, during the pause, reads only what changed since the first. So autosave is off for a moment even after an hour of building, and even the very first backup of a big world.

What counts as changed is cheap to tell: each region file's header records where every chunk sits and when it was written, so an unchanged chunk is recognised from 8 bytes and reused without being read. Other files are recognised by size and time.

## Storage

Each piece of data is named by its SHA-256 and stored once, however many backups use it. Region chunks are stored as Minecraft compressed them; other files are deflated, and big ones are cut at content-defined points, so a change near the start of a large file doesn't make the rest look new.

## Threads and timers

All backup work runs on one low-priority thread, one job at a time, with optional disk and upload limits. The only timer is the schedule: one task set for the next backup, and set again after it. Checking older data and giving back space ride along after a scheduled backup when they're due. A join listener runs only with `skip-when-idle`.

Between backups, xBackup holds a list of backups in memory and nothing else. During one, the index of stored pieces takes about 65 bytes per piece: roughly 65 MB for a world of a million chunks.
