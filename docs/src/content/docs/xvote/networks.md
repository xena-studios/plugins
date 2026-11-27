---
title: Networks
description: "xVote on a Velocity or BungeeCord network: one receiver on the proxy, each vote paid once, on the server the player is on, or the first one they join."
sidebar:
  order: 2.7
---

On a network, xVote runs on the proxy and on every server, all sharing one MySQL or MariaDB database. The proxy receives every vote on one port; the server the player is on pays it.

## Set it up

1. Give the proxy and every server the same `storage` (`type: mysql`, the same database and `table-prefix`) and `network.enabled: true`, in each one's `plugins/xVote/config.yml` (`plugins/xvote/` on Velocity). Use the same `counting.time-zone` everywhere.
2. Turn the servers' receivers off (`receiver.enabled: false`): the proxy's is the network's.
3. Point every list at the proxy's address and its `receiver.port`. Take over NuVotifier's keys and tokens on the proxy, as on a server: see [Switching](/xvote/switching/).
4. Restart the proxy and the servers. Each console says `sharing votes with the network`; `/xvoteproxy status` on the proxy says `network mode on`.

## What happens to a vote

- **The proxy stores and counts it**, in the shared database: who it's for (the proxy knows everyone on the network by name), the player's totals and streak, and the party's count. Repeats are dropped there, as on one server. The proxy never pays anything: it has no items, commands or economy.
- **The server the player is on pays it.** The proxy tells that server at once, on the proxy layer (`xena:xvote`, signed with the network's secret, so a player's client can't fake it), and the server takes the vote in the database and gives its rewards. The database's change feed carries the same news to every server within `network.check-every` seconds, so a message that doesn't arrive loses nothing.
- **A player who's offline is paid where they join.** The vote waits in the database, and the first server they join takes it. A name that has never joined waits for that name, as on one server.
- **Never twice.** Each vote is taken by one server in the database (`UPDATE ... WHERE claim IS NULL`) before it's paid, so two servers can't both pay it, however they race. A player who leaves before their reward reaches them gives the vote back, for the next server they're on.
- **The vote party** counts the network's votes; when it starts, every server rewards its own players.

A Fabric server on a network works through the database alone (Fabric has no end of the proxy layer yet): its players are paid within `network.check-every` seconds.

## Without xVote on the proxy

Servers sharing the database work as 1.x did: run the receiver on one server, point the lists at it, and every vote is paid on whichever server the player is on. xVote on the proxy without the shared database stores votes no server reads, and says so.

## NuVotifier on the proxy

If your proxy runs NuVotifier rather than xVote:

- **Its `proxy` forwarding method** sends each vote to each server's receiver as a v2 vote, signed with a token: give each server's xVote that token (`/xvote token <name>`), and nothing else is needed.
- **Its `pluginMessaging` method** sends votes over a plugin channel. Set `receiver.nuvotifier-channel` to that channel (`nuvotifier:votes`) on each server (Bukkit). A player's client can send on any channel, so only do this when the proxy drops players' messages on it; the `proxy` method can't be forged.

xVote on the proxy is simpler: one receiver, and votes queued for offline players in the database rather than in NuVotifier's cache.
