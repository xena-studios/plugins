---
title: xRewind
description: "xRewind: a free block logger with rollback for Paper and Folia servers, with CoreProtect's commands and an import of your CoreProtect history."
sidebar:
  label: Overview
  order: 1
---

xRewind is a block logger with rollback for Paper and Folia. When someone griefs spawn or empties the shared chest, it shows you who placed, broke or took what, where and when, and puts it back.

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon. Automatic purges included (30 days by default).
- **New Minecraft versions the same week.** It uses only Paper's API, never the server's internals, so an update rarely breaks it.
- **You won't feel it running.** Nothing touches the database on a game thread: each change is queued in about 40 nanoseconds and written in batches by one background thread. Logging you turn off isn't listened for at all. See [Performance](/xrewind/performance/).
- **Rollback that doesn't freeze the server.** A rollback works out each block's final state once, then applies it a few milliseconds per tick, or region by region on Folia. Preview it first, and undo it after.
- **Easy to switch to.** CoreProtect's command syntax, so habits and guides carry over, and your CoreProtect history imported with one command: see [Switching from CoreProtect](/xrewind/switching/).

## What it logs

On by default: blocks placed and broken (with what they held, and the torches, signs and rails that fall off with them), explosions (TNT is put down to whoever lit it), fire, buckets and liquids that destroy something, pistons, mobs that change blocks (endermen, withers, falling sand), sign text, items put in and taken out of containers, kills, and joins and quits.

Off unless you turn them on: hopper transfers, door and lever clicks, leaf decay, chat and commands. Each kind is a switch under `log` in the config, and one that's off isn't even listened for.

A rollback brings back what a broken block held: a chest's items, a sign's text.

## Commands

`/xr inspect` to click blocks for their history, `/xr lookup` to search it with CoreProtect's parameters (`u:` `t:` `r:` `a:` `i:` `e:` `w:`), `/xr rollback` and `/xr restore` with the same parameters (and `#preview`), `/xr undo`, and `/xr status` to see that logging is keeping up. See [Commands](/xrewind/commands/).

## Storage

SQLite out of the box, in one file in the plugin's folder, or MySQL/MariaDB for networks. No driver is bundled: Paper and Folia already ship them. See [Configuration](/xrewind/configuration/).

## What else it does

- **Every change applies on reload**, even what's logged and where it's stored. No restarts.

## Getting started

1. [Install it](/start/installing/). It logs to SQLite with nothing to set up.
2. Choose what's logged, and where it's stored: [Configuration](/xrewind/configuration/).
3. Learn the lookup parameters, how a rollback works, and which permission each command needs: [Commands](/xrewind/commands/).
4. Coming from CoreProtect? Bring your history: [Switching from CoreProtect](/xrewind/switching/).
