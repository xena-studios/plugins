---
title: xStats
description: "xStats: player stats, daily, weekly and monthly leaderboards, placeholders and a stats website your server hosts itself, for Paper and Folia servers."
sidebar:
  label: Overview
  order: 1
---

xStats gives players their stats, everyone leaderboards, and you placeholders and a stats website your server hosts itself, without a separate web host, an account or a sign-in. It reads the stats Minecraft already counts, adds playtime that leaves out time away from the keyboard, and can keep any PlaceholderAPI value as a stat too.

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **History from day one.** On first start xStats reads every player's stats from the world's own files, so leaderboards aren't empty.
- **Leaderboards by day, week, month and all time.** `/lb kills monthly`, placeholders such as `%xstats_top_kills_monthly_1_name%`, and boards in the world, with banned players and anyone you choose left out.
- **A website, built in.** Player search, profiles with heads and 30-day charts, leaderboards and server totals, in your server's name, logo and colours. It's served by xStats itself, or copied to GitHub Pages, Cloudflare Pages or your own domain. There's a documented JSON API for building your own.
- **Private when players want.** `/stats private` takes a player off the website. xStats stores no IP addresses and shows only the stats you choose.

## How it runs

- **One save, every five minutes.** It's xStats' only repeating task. It reads each online player's stats on that player's own thread, a few players a tick, so a full server is never read in one tick. Reading Minecraft's stats is a lookup in the counters the server already keeps: no listener for kills, blocks or movement.
- **Leaderboards are worked out during the save** and kept in memory. Placeholders, `/leaderboard`, boards in the world and the website's leaderboard pages never ask the database.
- **Small database.** Each player has one running total per stat, plus one row per stat for each day it went up (only for stats you give periods). Days older than 90 are added into their month.
- **AFK-aware playtime** comes from the same save: a player who hasn't moved their camera since the last one isn't counted.
- **Nothing is lost to a crash** of Minecraft's own stats: xStats keeps the last reading of each counter, and the next reading makes up the difference. Playtime and placeholder stats lose at most one save's worth.
- **The website** runs on Java's built-in web server, only while it's on, with a limit per visitor and the pages everyone sees made once per save.

## Storage

Stats are kept in SQLite out of the box, in `plugins/xStats/stats.db`, or in MySQL/MariaDB, in [documented tables](/xstats/database/). Several servers can share one MySQL database: see [Networks](/xstats/networks/).

## What else it does

- **Every change applies on reload**, except `storage`, `server-name` and `network`, which wait for a restart.

## Getting started

1. [Install it](/start/installing/). Stats are kept in SQLite out of the box, with nothing to set up.
2. Choose your stats and leaderboards: [Setting up](/xstats/setting-up/).
3. Turn on the website: [The website](/xstats/website/).
4. Every command and permission: [Commands](/xstats/commands/). Placeholders: [Placeholders](/xstats/placeholders/).
5. Several servers? [Networks](/xstats/networks/).
