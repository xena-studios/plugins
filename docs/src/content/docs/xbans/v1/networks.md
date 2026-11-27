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

4. If IPs are hashed (`ips.hash: true`), copy `plugins/xBans/ip-salt.txt` from one server to all the others, so they hash the same address the same way. A server without it, or with a different one, says so in its console and lets nobody join until it has the right one.

Then `/xbans reload` on each.

## Turning on IP hashing

Hashing an existing network's IPs rewrites what's stored, so do it with every server stopped:

1. Stop every server.
2. On one server, set `ips.hash: true` and start it. It hashes what's stored and makes `plugins/xBans/ip-salt.txt`.
3. Copy that `ip-salt.txt` to every other server's `plugins/xBans/`, and set `ips.hash: true` there too.
4. Start the others.

If two servers turn it on at once anyway, only one hashes: the other says its `ip-salt.txt` isn't the right one and lets nobody join until you copy the first one's across. A server still running without hashing would keep storing plain addresses meanwhile, which is why they should all be stopped.

## What's shared

- **A ban on one server kicks the player from whichever server they're on**, and every server's ban list has it within `check-every` seconds. Unbans, mutes and unmutes travel the same way. Broadcasts are shown on every server.
- **Every server reads the same history**, so `/history`, `/alts` and `/checkban` show the same everywhere.
- **Warning actions run once**, on the server where the warning was given.
- **A server that misses a change catches up.** If a server can't read a punishment another made (MySQL dropped for a moment), it tries again, then reads every ban and mute in force afresh, and kicks anyone it finds banned. If that fails too, it tries again every 15 seconds until it works, reading everything in force once however many changes it missed. `/unban` reads the database, not the server's memory, so it lifts a ban even on a server that missed it.

Network mode adds one check every `check-every` seconds, off the server's threads: a single quick query for anything newer than the last change it saw. It's the plugin's only repeating task (apart from the 15-second retries while the database is down), and it's off unless `network.enabled` is on with a MySQL database.

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
