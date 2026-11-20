---
title: Web API
description: xStats' read-only JSON API, for building your own stats site, bot or dashboard.
sidebar:
  order: 6
---

When the [website](/xstats/website/) is on, xStats serves a read-only JSON API beside it. Everything the built-in site shows comes from here, so anything it does, your own site can too.

- **Versioned.** Every path starts with `/api/v1/`. A change that removes or renames a field comes as `/api/v2/`, with v1 kept alongside. New fields can be added to v1 at any time.
- **Read-only.** `GET` and `HEAD` only. Anything else gets `405`.
- **CORS** for the sites in `web.cors-origins` (any site by default). They can send `If-None-Match` and read `ETag` and `Retry-After`.
- **Cached.** Responses carry `ETag` and `Cache-Control` headers and are gzipped when asked (the gzipped copy has its own `ETag`). Send `If-None-Match` to get a `304` when nothing changed. Leaderboards, their summaries and search are `no-cache`: keep them, but ask again each time, so a player who goes private drops out at once.
- **Rate-limited** per visitor: `429` with `Retry-After` past `web.requests-per-minute`, five times that for heads.
- **Errors** are JSON: `{"error": "Not found."}` with the status code.

Values are whole numbers in each stat's unit (`unit`): `count`, `time` in seconds, `distance` in centimetres, `damage` in tenths of a health point. Each comes with `display`, the same value as players read it. Times are milliseconds since 1970 (UTC).

## `GET /api/v1/server`

The server, its stats and their totals. Made at each save, cached for a minute.

```json
{
  "api": 1,
  "version": "1.0.0",
  "name": "My Server",
  "tagline": "",
  "logo": "/brand/logo.png",
  "colours": {"accent": "#3ec407", "background": "#0f1419", "surface": "#1a222b", "text": "#e6edf3"},
  "avatar": "https://mc-heads.net/avatar/{uuid}/{size}",
  "players": 1234,
  "saved": 1795000000000,
  "network": false,
  "periods": [{"id": "daily", "name": "Today"}, {"id": "weekly", "name": "This week"},
              {"id": "monthly", "name": "This month"}, {"id": "all", "name": "All time"}],
  "stats": [
    {"id": "kills", "name": "Player kills", "unit": "count", "decimals": 0, "periods": true,
     "total": 5821, "totalDisplay": "5,821"}
  ]
}
```

`logo` and `avatar` may be paths on this server (starting with `/`) or full addresses. `avatar` has `{uuid}`, `{name}` and `{size}` to fill in.

## `GET /api/v1/leaderboard/{stat}/{period}`

One leaderboard: up to `leaderboards.size` places, best first, without players who are hidden or left out. `{period}` is `daily`, `weekly`, `monthly` or `all`; a stat without periods only has `all`. Made at each save, and again straight away when someone runs `/stats private`.

```json
{
  "stat": "kills",
  "period": "monthly",
  "saved": 1795000000000,
  "entries": [
    {"rank": 1, "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch", "value": 42, "display": "42"}
  ]
}
```

Players with the same value share a place. Places are counted among the players the API shows, so a place never gives away that someone private is above; in game, where private players still show, places can differ.

## `GET /api/v1/leaderboards/{period}`

The top five of every stat for one period, for an overview page: `{"period": "all", "saved": ..., "boards": [{"stat": "kills", "entries": [...]}]}`.

## `GET /api/v1/search?q={name}`

Players whose names start with `q` (1 to 16 letters, digits, `_`, and the `.`, `*`, `+`, `~` or `-` that start Bedrock players' names through Floodgate), at most 10: `{"players": [{"uuid": "...", "name": "Notch"}]}`.

## `GET /api/v1/player/{name or uuid}`

One player's page, by uuid or by name. Link to players by uuid: it never changes, and it's how the built-in site and `/stats` link (`#/player/<uuid>`). `404` for a player xStats doesn't know, or one who ran `/stats private`. A `rank` is the place among players the API shows.

```json
{
  "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "name": "Notch",
  "firstJoin": 1790000000000,
  "lastSeen": 1795000000000,
  "online": false,
  "stats": [
    {"id": "kills", "value": 42, "display": "42", "rank": 3,
     "periods": {"daily": {"value": 2, "display": "2", "rank": 1},
                 "weekly": {"value": 9, "display": "9", "rank": 2},
                 "monthly": {"value": 20, "display": "20", "rank": 3}}}
  ],
  "sections": [
    {"id": "xvote_votes", "title": "Voting", "lines": [{"label": "Votes this month", "value": "12"}]}
  ],
  "history": {"from": 20400, "stats": {"kills": [0, 1, 0, 3, "... 30 numbers, one per day"]}},
  "servers": [{"server": "survival", "stats": {"kills": 30}}]
}
```

- `rank` is 0 when they're not on that leaderboard.
- `sections` are what other plugins add through xStats' [developer API](/xstats/developer-api/).
- `history` is how much each stat with periods went up on each of the last 30 days; `from` is the first day, as days since 1970-01-01.
- `servers` is only there in [network mode](/xstats/networks/): each server's all-time values.
- `online` is as of the last save.

## `GET /api/v1/head/{uuid}`

A player's head, as a PNG, when `web.avatars.proxy` is on. `404` otherwise, and for players xStats doesn't know.
