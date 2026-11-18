---
title: Networks
description: Share bans between servers behind Velocity or BungeeCord with one MySQL database, and scope punishments to one server.
sidebar:
  order: 4
---

xBans 1.0 runs on each backend server, not on the proxy. Servers that share one MySQL or MariaDB database share their punishments: each checks bans at login from its own memory, and a ban made on one reaches the others within a few seconds.

## Setting up

On every backend server:

1. Point `storage` at the same database, with the same `table-prefix`:

   ```yaml
   storage:
     type: "mysql"
     table-prefix: "xbans_"
     mysql:
       host: "db.example.com"
       port: 3306
       database: "minecraft"
       username: "xbans"
       password: "..."
   ```

2. Turn on network mode:

   ```yaml
   network:
     enabled: true
     check-every: 3
   ```

3. Give each server its own name, for `server:` scopes:

   ```yaml
   scopes:
     server-name: "survival"
   ```

4. If IPs are hashed (`ips.hash: true`), copy `plugins/xBans/ip-salt.txt` from one server to all the others, so they hash the same address the same way. A server with a different salt says so in its console.

Then `/xbans reload` on each.

## What's shared

- **A ban on one server kicks the player from whichever server they're on**, and every server's ban list has it within `check-every` seconds. Unbans, mutes and unmutes travel the same way. Broadcasts are shown on every server.
- **Every server reads the same history**, so `/history`, `/alts` and `/checkban` show the same everywhere.
- **Warning actions run once**, on the server where the warning was given.

Network mode adds one check every `check-every` seconds, off the server's threads: a single quick query for anything newer than the last change it saw. It's the plugin's only repeating task, and it's off unless `network.enabled` is on with a MySQL database.

## Server scopes

By default every punishment applies on every server. `server:<name>` limits one to a single server:

```
/ban Steve 7d server:survival Griefing
```

Steve can still join the lobby and the other servers. `scopes.default-scope: local` makes punishments apply only on the server they were made on, unless `server:global` (or `-g`) is given. Choosing a scope needs `xbans.scope`.

`/unban Steve` lifts all of Steve's bans, on every server. `/unban Steve server:survival` lifts only that one.

## xLimbo

xBans on a limbo server, sharing the network's database, enforces the same bans. There's nothing else to set up.

## Not yet

A plugin for the proxy itself, which would check bans before a player reaches any server, comes after 1.0.
