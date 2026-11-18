---
title: xBans
description: "xBans: free, open-source bans, mutes, warnings and kicks for Paper and Folia, with LiteBans' commands, flags and durations, and a one-command import from LiteBans."
sidebar:
  label: Overview
  order: 1
---

**Bans, mutes and warnings, the LiteBans way.**

xBans is a punishment plugin for Paper and Folia: bans, temporary bans, IP bans, mutes, warnings and kicks, with history, alts and staff limits. It uses LiteBans' commands, flags and duration format, so your staff's habits carry over, and it imports your LiteBans, vanilla and EssentialsX bans with one command.

- **Free and open source.** Every feature, no premium version.
- **LiteBans' syntax.** `/ban Steve -s 7d server:survival Hacking`, `/tempmute Alex 1d2h`, `/unban #123`, `/history`, `/alts`. Silent (`-s`), extra silent (`-S`) and public (`-p`) punishments, and server scopes. See [Commands](/xbans/commands/).
- **Bans that hold.** Every ban and mute in force is kept in memory, loaded before anyone can join, so the join check never waits on the database and keeps working if MySQL drops for a moment. A ban made while someone is connecting still catches them. Mutes block chat, `/msg`, `/r`, `/me` and the rest (by any alias, `/essentials:msg` included), signs and books.
- **Nothing is ever deleted.** An unban is recorded next to the ban, with who, when and why. Only `/prunehistory` deletes, when you ask it to.
- **Templates and escalation.** `/ban Steve hacking` gives 7 days, then 30, then permanent. Three warnings can mute for an hour. All in the config.
- **Staff limits.** Weights so helpers can't ban moderators, and longest durations per group.
- **IP privacy.** IPs can be stored as salted hashes: alts and IP bans still work, with no list of addresses to leak. See [Privacy](/xbans/privacy/).
- **Networks without a proxy plugin.** Servers that share a MySQL database share bans: a ban on one kicks the player from the others within seconds. See [Networks](/xbans/networks/).
- **Replaces vanilla** `/ban`, `/pardon`, `/ban-ip`, `/pardon-ip`, `/kick` and `/banlist`.

Current version: **1.0.0**, for Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. [Download on Modrinth](https://modrinth.com/plugin/xbans).

## You won't feel it running

The join check and the chat check are a couple of lookups in memory each: no database query, no waiting. Punishments are written by one background thread. History pages are read by key range, so the 300th page of a long history is as quick as the first. There are no repeating tasks on a single server; network mode adds one check every few seconds, off the server's threads.

## Storage

SQLite out of the box, in one file in the plugin's folder, or MySQL/MariaDB for networks. No driver is bundled: Paper and Folia already ship them. See [Configuration](/xbans/configuration/).

## Start here

1. [Install it](/start/installing/). It works out of the box with SQLite.
2. Give your staff the permissions they need: [Commands](/xbans/commands/).
3. Set up templates, staff limits and what mutes block: [Configuration](/xbans/configuration/).
4. Coming from LiteBans, EssentialsX or vanilla bans? Bring them: [Switching](/xbans/switching/).
