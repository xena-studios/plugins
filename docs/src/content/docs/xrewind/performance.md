---
title: Performance
description: What xRewind costs a running server, and how it stays that way.
sidebar:
  order: 5
---

A block logger runs on every block change on your server, so what it costs matters more than for almost any other plugin.

## On the game's threads: about 40 nanoseconds

When something happens, a listener copies what the event already has (who, where, the block before and after) into a small record and adds it to a queue. It doesn't look anything up, write anything or wait for anything. That takes about 40 ns, measured in xRewind's tests, against a budget of 2 µs. It's the same on Folia's region threads, since there's no lock to share.

Logging you've turned off isn't listened for at all, so it costs nothing.

## The writer

One background thread turns names, worlds and block states into numbers, and writes up to 10,000 changes per transaction: every half second, or as soon as 2,000 are waiting. Each row is a handful of numbers, so the database stays small, and SQLite runs in WAL mode, so lookups never wait for it.

If the database is slow or down, changes wait in memory: the console is warned at 100,000 waiting. Past a million, new changes are counted and dropped so the server never runs out of memory, and `/xr status` shows how many were missed. Nothing else is ever dropped: a write that fails is tried again.

## Lookups and rollbacks

Lookups run off the game's threads. A rollback works out what to change off them too, then sets blocks 10 milliseconds per tick (`rollback.ms-per-tick`), chunk by chunk, with the next chunks loading in the background, or region by region on Folia. Each block is set once, however often it changed.

## Purges

`purge.keep-days` (30 by default) deletes older history once a day, a few thousand rows per transaction, so logging carries on alongside. SQLite gives the space back a little at a time instead of locking the file.

## Storage

SQLite needs nothing set up. For a big server or a network, MySQL or MariaDB handles more history and lets several servers share one database (give each its own `table-prefix`). Paper and Folia include both drivers, so xRewind's jar has none.
