---
title: Networks
description: One set of stats, one website and network-wide leaderboards for several servers sharing a MySQL database, and xStats on Velocity for the whole network.
sidebar:
  order: 7
---

Several servers can share one MySQL or MariaDB database. Each keeps its own players' numbers apart under its own name, and they're added up for the network: `/leaderboard`, placeholders, boards and the website count every server.

On each server:

```yaml
server-name: "survival"   # different on every server
storage:
  type: mysql
  mysql:
    host: "db.example.com"
    database: "stats"
    username: "stats"
    password: "..."
network:
  enabled: true
```

Restart each server after changing `storage`.

- **Each server reads its own players.** Minecraft counts stats per server, so a player's kills on survival and on creative are two numbers, added up for the network. A placeholder stat, such as a balance, isn't added up: it's the latest value any server read. So is the family's `xmoney_balance`.
- **Saves reach the others.** After each save, the other servers hear about it through the database (every `network.check-every` seconds, 3 by default), and read the network's numbers at their next save. `/stats private` reaches them within those few seconds.
- **One website** for the whole network: turn it on on one server only, or on the proxy, and point your domain at that one.
- **Per server on profiles.** A player's page on the website shows each server's numbers too.

Without `network.enabled`, servers sharing a database each see only their own numbers.

## On the proxy

Add xStats to Velocity with the same `storage` and `network.enabled: true` as the servers. It reads every server's stats from the database, again whenever a server saves (checked every `proxy.refresh-seconds` at most), and answers from memory:

- **`/networkstats [player]`** and **`/networkleaderboard <stat> [period] [page]`**, wherever the player is, even on a server without xStats. They're named so that `/stats` and `/leaderboard` still reach the player's own server.
- **The website** for the whole network, with `web.enabled` on the proxy instead of on a server.
- **`/xstatsproxy status`** shows the database, the network's players and servers, and when they were last read.

The proxy counts nothing itself: the servers do. With SQLite, which the servers can't share, it has no server's stats, and says so in the console.

The proxy's `server-name`, `collecting`, `leaderboards.boards` and `integrations` do nothing; `stats`, `periods` and `leaderboards` should match the servers'.
