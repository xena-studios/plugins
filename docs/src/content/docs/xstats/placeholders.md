---
title: Placeholders
description: "xStats' placeholders: PlaceholderAPI on Bukkit and Text Placeholder API on Fabric, for scoreboards, TAB, holograms and ajLeaderboards."
sidebar:
  order: 4.7
---

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed on Bukkit, xStats adds these. Each is answered from memory, from the last save, so they're cheap enough for a scoreboard that refreshes every tick.

`<stat>` is a stat's id from the config, such as `kills` or `mob_kills`, or one of the family's, such as `xvote_votes`. `<period>` is `daily`, `weekly`, `monthly` or `all`; leave it out for all time. Only stats with `periods: true` (and the family's) have daily, weekly and monthly numbers.

| Placeholder | What it shows |
| --- | --- |
| `%xstats_<stat>%` | The player's all-time value, as players read it: `1,234`, `3h 12m`, `1.2 km`. |
| `%xstats_<stat>_<period>%` | Their value for a period: `%xstats_kills_monthly%`. |
| `%xstats_<stat>_raw%`, `%xstats_<stat>_<period>_raw%` | The bare number, for plugins that sort or add up: seconds for times, centimetres for distances. |
| `%xstats_rank_<stat>%`, `%xstats_rank_<stat>_<period>%` | Their place on the leaderboard, or blank if they're not on it. |
| `%xstats_top_<stat>_<n>_name%` | Who's in place `n`: `%xstats_top_kills_1_name%`. Add a period: `%xstats_top_kills_monthly_1_name%`. |
| `%xstats_top_<stat>_<n>_value%`, `%xstats_top_<stat>_<n>_raw%` | Their value, written out or as a bare number. |
| `%xstats_kd%` | Kills per death, from the `kills` and `deaths` stats: `2.50`. |

Leaderboard placeholders reach as far as `leaderboards.size` (100 places); past that, they're blank.

**ajLeaderboards** works with the `_raw` placeholders, but xStats' own `top` placeholders and boards already give you leaderboards per day, week and month without it.

## On Fabric

With [Text Placeholder API](https://modrinth.com/mod/placeholder-api) installed, the same placeholders are `%xstats:stat <params>%`, where `<params>` is what follows `xstats_` above: `%xstats:stat kills_monthly%`, `%xstats:stat top_kills_1_name%`, `%xstats:stat rank_playtime%`. Kills per death is `%xstats:kd%`.
