---
title: xRewind
description: Every change, logged. Any of it, undone. A block logger with rollback for Paper and Folia, being built.
sidebar:
  label: Overview
  order: 1
  badge:
    text: Soon
    variant: note
---

**Every change, logged. Any of it, undone.**

xRewind is a block logger with rollback for Paper and Folia: who placed, broke or took what, where and when, and a way to undo it.

:::note[Being built]
xRewind isn't released yet. This page describes what 1.0 will do; follow along in [the plan](https://github.com/xena-studios/plugins/issues/20).
:::

- **Every feature free.** No early-access builds and nothing behind a Patreon, automatic purges included.
- **New Minecraft versions the same week.** It uses only Paper's API, never the server's internals, so an update rarely breaks it.
- **You won't feel it running.** Nothing touches the database on a game thread: each change is queued as a few numbers and written in batches by one background thread. Logging you turn off isn't listened for at all.
- **Rollback that doesn't freeze the server.** A rollback works out each block's final state once, then applies it a few milliseconds per tick, or region by region on Folia. Preview it first, and undo it after.
- **Easy to switch to.** CoreProtect's command syntax, so habits and guides carry over, and your CoreProtect history imported.

## What 1.0 will log

Blocks placed and broken (and the torches, signs and rails that fall off with them), explosions traced to whoever lit them, fire, liquids that destroy something, pistons, mobs that change blocks, sign text, items put in and taken out of containers, kills, and joins and quits. Noisy things, like hopper transfers, leaf decay and door clicks, are off unless you turn them on. Chat and command logging are off by default.

## Commands

`/xr inspect` to click blocks for their history, `/xr lookup` to search it with CoreProtect's parameters (`u:` `t:` `r:` `a:` `i:` `e:`), `/xr rollback` and `/xr restore` with the same parameters (and `#preview`), `/xr undo`, and `/xr status` to see that logging is keeping up. See [Commands](/xrewind/commands/).

## Storage

SQLite out of the box, in one file in the plugin's folder, or MySQL/MariaDB for networks. No driver is bundled: Paper and Folia already ship them. See [Configuration](/xrewind/configuration/).

Needs Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer.
