---
title: Commands
description: xStats' commands for players and staff, and its permissions.
sidebar:
  order: 3
---

## Players

| Command | What it does |
| --- | --- |
| `/stats [player]` | A player's stats, with their place on each all-time leaderboard, sections other plugins add, and a link to their page on the website when `web.public-url` is set. Works for players who are offline. |
| `/stats private` | Hides you from the website, or shows you again. Needs `xstats.private`, which everyone has. |
| `/leaderboard <stat> [daily\|weekly\|monthly\|all] [page]` (`/lb`) | A leaderboard, a page at a time. The stat is its id (`mob_kills`) or its name (`Mobs_killed`). |

Both commands can be turned off, given other aliases or a permission under `commands` in the config. Their permissions are blank (everyone) by default. The leaderboard command isn't `/top` because xUtilities' `/top` takes you to the highest block; without xUtilities, add `top` to `commands.leaderboard.aliases`.

## Staff

Every `/xstats` command needs `xstats.admin`, which operators have.

| Command | What it does |
| --- | --- |
| `/xstats status` | The database, players known, the last save, rows in each table, the website, and whether xStats' table of Minecraft's stats matches this server. |
| `/xstats save` | Saves now, instead of waiting for the next save. |
| `/xstats import` | Reads players' history from the world's stats files again. Never overwrites what's counted: see [History](/xstats/setting-up/#history-from-before-xstats). |
| `/xstats board add <stat> [period] [<world> <x> <y> <z>]` | Places a leaderboard in the world: where you stand, or at the coordinates. |
| `/xstats board list` | Every board, with its id. |
| `/xstats board remove <id>` | Takes a board down. |
| `/xstats web test` | Starts a test copy of the website on a free port on this machine, checks every kind of page, and stops it. |
| `/xstats web export` | Writes the website to `plugins/xStats/site/`, to host anywhere: see [Host it anywhere](/xstats/website/#host-it-anywhere). |
| `/xstats reload` | Applies `config.yml`. |
| `/xstats info` | The version and what's running. |

## Permissions

| Permission | Who has it | What it does |
| --- | --- | --- |
| `xstats.admin` | Operators | Every `/xstats` command. |
| `xstats.private` | Everyone | `/stats private`. |
| `xstats.staff` | Operators | On `/stats`, sections other plugins only show staff, such as a full punishment history. |
| `xstats.leaderboard.exclude` | Nobody | Left out of every leaderboard. Give it to staff who shouldn't compete. |
