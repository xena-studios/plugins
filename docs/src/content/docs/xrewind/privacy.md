---
title: Privacy
description: What xRewind stores about players, and how to delete a player's chat, commands and sessions.
sidebar:
  order: 6.5
---

xRewind keeps its history in your own database. Nothing of it leaves your server.

## What's stored

| Table | What | Why |
| --- | --- | --- |
| `user` | Each player's uuid and last name, and each source's name (`#tnt`). | Who made a change. |
| `block`, `container`, `entity` | Blocks placed, broken and used, items put in and taken out, kills: who, where, when, and what. | Lookups and rollbacks. |
| `text` | Sign text; chat and commands only when `log.chat` or `log.commands` is on (off by default). | Lookups. |
| `session` | Joins and quits, with where the player was. | Lookups. |
| `world`, `state`, `item` | Worlds' names, block states and items, each stored once. | Every row refers to them by number. |

Chat and commands are personal data in much of the world, including under the EU's GDPR: logging them needs a reason, such as moderating your server, and should be said in your server's rules or privacy notice. That's why they're off by default, and why looking them up needs its own permission, `xrewind.lookup.chat`.

## Deleting a player's data

`/xrewind forget <player>` (by name or uuid, online or not) deletes a player's chat, commands, joins and quits, and the family's player cache entry, once confirmed.

What they did to the world (blocks, containers, kills, signs) stays, as a ban stays in xBans: it's a record of what happened on your server, which a rollback needs to undo griefing, and a griefer could otherwise erase their own tracks by asking. Their name stays beside those changes. If you have to delete them too, `purge.keep-days` and `/xr purge` delete everything older than a time, for everyone.
