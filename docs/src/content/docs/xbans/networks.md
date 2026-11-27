---
title: Networks
description: "Bans for a whole Velocity or BungeeCord network: banned players turned away at the proxy, one MySQL database for every server, and punishments scoped to one server."
sidebar:
  order: 4
---

On a network, xBans runs on every server and on the proxy, all sharing one MySQL or MariaDB database. The database is where every punishment is kept, and the servers and the proxy each keep what's in force in memory, from it:

- **A banned player never reaches a server.** The proxy turns them away as they log in, with their ban screen in their language. A ban scoped to one server (`server:survival`) keeps them off that one: the proxy refuses to send them there, and lets them stay where they are.
- **A ban or kick on any server takes the player off the network at once.** The server tells the proxy, which disconnects them wherever they are. A mute made on one server blocks their chat on all of them, within `check-every` seconds.
- **Every server reads the same history**, so `/history`, `/alts` and `/checkban` show the same everywhere, and the proxy's console has the same commands.

Without xBans on the proxy, each server still checks bans at its own login, as 1.x did: the player reaches the proxy, then is turned away by the server.

## Setting up

1. **Every server and the proxy point `storage` at the same database**, with the same `table-prefix`:

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

2. **Turn on network mode** on each:

   ```yaml
   network:
     enabled: true
     check-every: 3
   ```

3. **Give each server its name**, the one the proxy gives it (`velocity.toml`'s `[servers]`, BungeeCord's `servers:`), so the proxy knows which servers a scoped ban keeps the player off:

   ```yaml
   scopes:
     server-name: "survival"
   ```

4. **Give the network its secret**, the same on the proxy and every server: `plugins/Xena/proxy.secret`. On Velocity with modern forwarding there's nothing to set: xBans uses the forwarding secret the proxy and each Paper server already share. A BungeeCord proxy that finds none writes a new one to its `plugins/Xena/proxy.secret` and says to copy it to each server. Without it, everything still works through the database, only the proxy hears of a ban made on a server a few seconds later.

5. **If IPs are hashed** (`ips.hash: true`), copy `plugins/xBans/ip-salt.txt` from one server to every other server and the proxy, so they hash the same address the same way. One without it, or with a different one, says so in its console and lets nobody join until it has the right one.

6. Install xBans' Velocity or BungeeCord jar on the proxy and restart it; `/xbans reload` on each server. Set up the proxy's forwarding (Velocity's `player-info-forwarding-mode`, BungeeCord's `ip_forward`) so the servers see players' real addresses.

The proxy's admin command is `/xbansproxy` (`/xbp`): `/xbansproxy reload`, `debug` and the rest. The punishment commands are the same on the proxy, from its console or for players with the permissions there; a player's `/ban` typed in game goes to their server, as before.

## When something is down

xBans never lets a banned player in because it can't check. It fails closed, and says so in the console:

| What's down | What happens |
| --- | --- |
| **The database, as a server or the proxy starts** | Nobody can join that server (or the network, at the proxy) until it opens and every ban in force is loaded: a player logging in meanwhile waits up to 10 seconds, then is told the server is still starting. It tries again every 15 seconds. |
| **The database, once it's open** | Every ban and mute in force stays in memory and keeps being enforced, at the proxy and on each server. New punishments can't be saved meanwhile: the command says so, and nothing is half made. A server that missed changes reads everything in force afresh once the database is back. |
| **The proxy's message to the proxy** (no one on the server to carry it, no secret) | The change still reaches the proxy through the database, within `check-every` seconds, and it disconnects the player then. |
| **xBans on the proxy** (not installed, or stopped) | Each server checks its own logins, as without a proxy. |

## Turning on IP hashing

Hashing an existing network's IPs rewrites what's stored, so do it with every server and the proxy stopped:

1. Stop every server and the proxy.
2. On one server, set `ips.hash: true` and start it. It hashes what's stored and makes `plugins/xBans/ip-salt.txt`.
3. Copy that `ip-salt.txt` to every other server's `plugins/xBans/` and the proxy's, and set `ips.hash: true` there too.
4. Start the others.

If two servers turn it on at once anyway, only one hashes: the other says its `ip-salt.txt` isn't the right one and lets nobody join until you copy the first one's across.

## What's shared

- **A ban on one server kicks the player from whichever server they're on**, and every server's ban list has it within `check-every` seconds. Unbans, mutes and unmutes travel the same way. Broadcasts are shown on every server; the proxy, whose servers tell their own players, adds none of its own.
- **Warning actions run once**, on the server where the warning was given.
- **A server that misses a change catches up.** If a server can't read a punishment another made (MySQL dropped for a moment), it tries again, then reads every ban and mute in force afresh, and kicks anyone it finds banned. If that fails too, it tries again every 15 seconds until it works. `/unban` reads the database, not the server's memory, so it lifts a ban even on a server that missed it.
- **[zChat](/zchat/)** reads xBans' mutes on each server, so a player muted on one can't chat or `/msg` across the network from another.

Network mode adds one check every `check-every` seconds, off the server's threads: a single quick query for anything newer than the last change it saw. It's off unless `network.enabled` is on with a MySQL database.

## Server scopes

By default every punishment applies on every server. `server:<name>` limits one to a single server:

```
/ban Steve 7d server:survival Griefing
```

Steve can still join the lobby and the other servers; the proxy refuses to send him to survival. `scopes.default-scope: local` makes punishments apply only on the server they were made on, unless `server:global` (or `-g`) is given. Choosing a scope needs `xbans.scope`. On the proxy, leave `default-scope` at `global`: a punishment made there is meant for the network, and the proxy's own `server-name` is no server's.

`/unban Steve` lifts all of Steve's bans, on every server. `/unban Steve server:survival` lifts only that one.

## xLimbo

xBans on a limbo server, sharing the network's database, enforces the same bans. There's nothing else to set up.
