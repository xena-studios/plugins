---
title: Database
description: xStats' tables, for building your own site or reports straight from MySQL.
sidebar:
  order: 8
---

xStats' tables are documented and kept stable, so you can build your own site or reports on them. Each name starts with `storage.table-prefix` (`xstats_`). Rows are numbers only: players, stats and servers are stored once each and referred to by id.

| Table | Columns | What it holds |
| --- | --- | --- |
| `players` | `id`, `uuid`, `name`, `name_lower`, `first_join`, `last_seen`, `hidden`, `excluded` | Every player xStats has seen. Times are milliseconds since 1970. `hidden` is 1 after `/stats private`; `excluded` is 1 for players with `xstats.leaderboard.exclude`. |
| `stats` | `id`, `name` | Each stat's id from the config, such as `kills`. |
| `servers` | `id`, `name` | Each `server-name`. |
| `totals` | `player`, `stat`, `server`, `value`, `raw`, `updated` | Each player's all-time value of each stat on each server. `raw` is the last reading of Minecraft's counter, or of a placeholder or another plugin's value; -1 when there's none. `updated` is when the value was last read or set: on a network, a placeholder stat (a balance) is the value of the row with the latest `updated`, not the sum. Indexed by `stat, server, value` for top-N queries. |
| `daily` | `player`, `stat`, `server`, `day`, `delta` | How much a stat went up on a day: only stats with periods, only days it changed. `day` is days since 1970-01-01, in `periods.timezone`. |
| `monthly` | `player`, `stat`, `server`, `month`, `delta` | Daily rows older than `periods.keep-daily-days`, added up per month. `month` is `year * 12 + month - 1`. |
| `boards` | `id`, `server`, `world`, `x`, `y`, `z`, `yaw`, `stat`, `period` | Leaderboards placed in the world. |
| `sources` | `stat`, `server`, `source` | Which of Minecraft's counters a stat added up on each server, so a stat whose `source` changes starts again from the new counters' value. |
| `meta` | `name`, `value` | The schema version, and whether the import ran on each server. |
| `changes` | | Only in network mode: the core's [change feed](/xstats/networks/), pruned to ten minutes. |

Values are in each stat's unit: seconds for times, centimetres for distances, tenths of a health point for damage, and a placeholder stat's `decimals` as whole numbers (12.34 with two decimals is 1234).

A player's network-wide total, and this month's top ten for a stat:

```sql
SELECT SUM(t.value) FROM xstats_totals t
  JOIN xstats_players p ON p.id = t.player JOIN xstats_stats s ON s.id = t.stat
  WHERE p.name_lower = 'notch' AND s.name = 'kills';

SELECT p.name, SUM(d.delta) AS total FROM xstats_daily d
  JOIN xstats_players p ON p.id = d.player JOIN xstats_stats s ON s.id = d.stat
  WHERE s.name = 'kills' AND d.day >= ? AND p.hidden = 0 AND p.excluded = 0
  GROUP BY p.id, p.name ORDER BY total DESC LIMIT 10;
```

Leave out players with `hidden = 1` anywhere public. Columns are only ever added, never renamed or removed, within a major version.
