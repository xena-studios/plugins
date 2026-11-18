---
title: Networks
description: One set of stats, one website and network-wide leaderboards for several servers sharing a MySQL database.
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

- **Each server reads its own players.** Minecraft counts stats per server, so a player's kills on survival and on creative are two numbers, added up for the network.
- **Saves reach the others.** After each save, the other servers hear about it through the database (every `network.check-every` seconds, 3 by default), and read the network's numbers at their next save. `/stats private` reaches them within those few seconds.
- **One website** for the whole network: turn it on on one server only, and point your domain at that one.
- **Per server on profiles.** A player's page on the website shows each server's numbers too.

Without `network.enabled`, servers sharing a database each see only their own numbers.
