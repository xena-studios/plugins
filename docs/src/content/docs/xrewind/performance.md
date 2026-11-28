---
title: Performance
description: What xRewind costs a running server, and how it stays that way.
sidebar:
  order: 5
---

A block logger runs on every block change on your server, so what it costs matters more than for almost any other plugin.

## On the game's threads: a small record

When something happens, the core's world hooks hand xRewind what the event already has (who, where, the block before and after), and it adds a small record to a queue. It doesn't look anything up, write anything or wait for anything: a block's state isn't even written out as text until the writer does it, off the game's threads. It's the same on Folia's region threads and Fabric's server thread.

Logging you've turned off isn't listened for at all, so it costs nothing: on Fabric, a kind of change nothing listens for costs one check of a field.

## The writer

One writer turns names, worlds and block states into numbers, and hands up to 10,000 changes at a time to the core's database writer, one transaction each: every half second, or as soon as 2,000 are waiting. Each row is a handful of numbers, so the database stays small, and SQLite runs in WAL mode, so lookups never wait for it.

If the database is slow or down, changes wait in memory: the console is warned at 100,000 waiting. Past a million, new changes are counted and dropped so the server never runs out of memory, and `/xr status` shows how many were missed. A write that fails is tried again; only a change the database itself won't take is dropped, with a warning in the console, and counted as missed too.

## Lookups and rollbacks

Lookups run off the game's threads. A rollback works out what to change off them too, then sets blocks 10 milliseconds per tick (`rollback.ms-per-tick`), chunk by chunk, with the next chunks loading in the background, or region by region on Folia, each region with its own 10 milliseconds. Each block is set once, however often it changed.

## Purges

`purge.keep-days` (30 by default) deletes older history once a day, a few thousand rows per transaction, so logging carries on alongside. SQLite gives the space back a little at a time instead of locking the file.

## Storage

SQLite needs nothing set up. For a big server or a network, MySQL or MariaDB handles more history and lets several servers share one database (give each its own `table-prefix`). xRewind's jar has no driver: Paper, Folia and Spigot include them, and on Fabric the one needed is downloaded once.

## Starting up

On Paper 26.3, in a two-core container, xRewind enables in about 45 ms of the server thread with its recorded startup list, and its classes take under 1.5 MB of metaspace: what only runs later (rollbacks, lookups, the writer's batches, the CoreProtect import, the inspector) loads the first time it's used.
