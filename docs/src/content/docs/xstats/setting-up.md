---
title: Setting up
description: Choose which stats xStats keeps, which get daily, weekly and monthly leaderboards, and place leaderboards in the world.
sidebar:
  order: 2
---

Drop the jar in `plugins/` and start the server. xStats starts counting straight away with 14 stats: playtime, sessions, player kills, deaths, mobs killed, blocks mined, distance travelled, fish caught, jumps, damage dealt, animals bred, villager trades, items enchanted and raids won.

## History from before xStats

On first start, xStats reads every player's stats from the world's stats folder (`world/players/stats/` on 26.x, `world/stats/` before), so leaderboards start with everyone's history. It runs off the server's threads and takes a moment for a big server; the console says when it's done.

- Imported history counts towards all-time numbers, not today, this week or this month.
- Playtime from before xStats is Minecraft's own, which counts AFK time too. From then on it's AFK-aware.
- Sessions from before xStats are the times each player left.
- A player xStats already counts keeps their numbers: importing never overwrites anything.

Run `/xstats import` to import again, such as after adding a stat: players who are offline get its history straight away, instead of when they next join.

## Choosing stats

Every stat is a section under `stats` in `config.yml`:

```yaml
stats:
  diamonds:
    name: "Diamonds mined"
    source: "mined:diamond_ore + mined:deepslate_diamond_ore"
    periods: true
```

`source` is where its numbers come from:

| Source | What it counts |
| --- | --- |
| `player_kills`, `jump`, `fish_caught`... | One of Minecraft's own stats, named as `/scoreboard` names them ([the full list](https://minecraft.wiki/w/Statistics#List_of_custom_statistic_names)). |
| `mined:<block>`, `crafted:<item>`, `used:<item>`, `broken:<item>`, `picked_up:<item>`, `dropped:<item>` | Blocks mined, or items crafted, used, broken, picked up or dropped. `*` adds up every one: `mined:*`. |
| `killed:<mob>`, `killed_by:<mob>` | Mobs killed, or deaths to a mob. `killed:*` is every mob. |
| `a + b + c` | Several of the above added up, as long as they count the same thing (all distances, say). |
| `xstats:playtime` | Time online, leaving out time a player didn't move their camera at all between two saves. |
| `xstats:sessions` | Times joined. |
| `papi:%placeholder%` | Any PlaceholderAPI value, read at every save and when the player leaves: `papi:%vault_eco_balance%`, a jobs level. |

The rest of a stat's settings:

- `name`: what players see.
- `unit`: `auto` works it out (distances in metres and kilometres, times in hours and minutes). Set `count`, `time`, `distance` or `damage` to choose.
- `decimals`: for placeholder values with decimals, such as `2` for a balance.
- `periods`: also count it per day, week and month, for `/lb kills daily`, placeholders and the website. Each day a player's stat goes up is one row in the database, so give periods only to the stats you want leaderboards of.
- `in-game`: show it on `/stats` and `/leaderboard`. Placeholders work either way.
- `website`: show it on the website and in its API.

The id (`diamonds`) is what placeholders and `/leaderboard` use, so it can't be one placeholders would read as something else: it can't start with `top_` or `rank_`, or end with `_raw`, `_all`, `_daily`, `_weekly` or `_monthly`. A stat removed from the config keeps its numbers in the database, and comes back with them if you add it again.

`time_since_death` and `time_since_rest` go back to 0 by themselves, so they can't be counted up; xStats refuses them as sources.

If another plugin takes some of a Minecraft stat away, xStats' number goes down with it. If a stat suddenly reads far lower (by more than a tenth, as when a player's stats file is deleted, or the world is rolled back that far), it's taken to have started again from 0, and xStats' all-time number keeps growing from there.

Minecraft counts distances in centimetres, damage in tenths of a health point and times in ticks. xStats keeps distances and damage as they are and times in seconds, and writes them all as players read them. Every Minecraft stat name goes through one table, checked against the server's own names: `/xstats status` says whether it matches.

## Placeholder stats

A `papi:` stat goes up and down with its placeholder, such as a balance. Only rises count towards today, this week and this month, and the first value xStats ever sees for a player counts towards all time only. A value that isn't a number (a blank, "none") is skipped. `1,234.56`, `$12.5k` and `2M` all read as numbers, colours included (`&#FF0000`, `<#ff0000>`, `§a`). A k, m, b or t right after the number multiplies it; a word after a space doesn't, so `5 blocks` is 5. On a network, a placeholder stat is the latest value any server read, not every server's added up, so a shared balance shows once, and goes down when it's spent on another server.

## Days, weeks and months

`periods.timezone` is when days start: blank uses the server's own time zone. Weeks start on `periods.week-starts`, Monday by default. Each day's numbers are kept for `periods.keep-daily-days` (90), then added into their month, so monthly and all-time numbers stay right and the database stays small. Changing the time zone or the first day of the week takes effect on `/xstats reload`: this week's and this month's numbers are worked out again for it.

## Leaving players out

Players with `xstats.leaderboard.exclude` are left out of every leaderboard, such as staff. Nobody has it unless you give it, operators included. With `leaderboards.exclude-banned` on, players banned with `/ban` are left out too, from the next save. Turning it off brings them back on `/xstats reload`. Their stats are still counted, and show on `/stats`.

## Boards in the world

`/xstats board add <stat> [daily|weekly|monthly|all]` places a leaderboard just above where you stand: a Paper text display, no hologram plugin needed. It's updated at every save. `/xstats board list` lists them with their ids, and `/xstats board remove <id>` takes one down. From the console, add the world and coordinates: `/xstats board add kills monthly world 0.5 80 0.5`.

The title, each line and the text when nobody's on it yet are in `leaderboards.boards`. Boards aren't saved with the world: each appears when its chunk loads, so one removed while its chunk was unloaded never leaves anything behind.

## Privacy

`/stats private` hides a player from the website and its API, straight away: their profile, search and its leaderboards. In-game leaderboards and placeholders still show them. Running it again shows them again. See [Privacy](/start/privacy/) for what the website shares.
