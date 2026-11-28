---
title: Network setup
description: Make xLimbo your proxy's fallback, and connect /join to your main server.
sidebar:
  order: 2
---

This is xLimbo as it has always been, and the default (`send-back.via: proxy`): xLimbo runs on its **own** Paper server behind your proxy, next to your main server. (For a limbo world on a server players play on, see [On one server](/xlimbo/on-one-server/).) Two things connect it:

1. Your **proxy** sends players to xLimbo when the main server is unavailable.
2. **`main-server`** in xLimbo's config names the server `/join` sends them back to.

## Velocity

In `velocity.toml`, add xLimbo as a server and put it after your main server in `try`:

```toml
[servers]
main = "127.0.0.1:25566"
limbo = "127.0.0.1:25567"

try = ["main", "limbo"]
```

Players join `main` when it's up and `limbo` when it isn't. When `main` goes down with players on it, Velocity's `failover-on-unexpected-server-disconnect` setting, which is on by default, moves them to the next server in `try`.

`/join` uses the BungeeCord plugin messaging channel, which Velocity supports while `bungee-plugin-message-channel` is `true` (the default).

## BungeeCord and Waterfall

In the proxy's `config.yml`, add xLimbo to `servers` and list it after your main server in the listener's `priorities`:

```yaml
servers:
  main:
    address: 127.0.0.1:25566
  limbo:
    address: 127.0.0.1:25567
listeners:
- priorities:
  - main
  - limbo
```

## Point /join at your main server

Set `main-server` in `plugins/xLimbo/config.yml` to your main server's name **in the proxy's config** (`main` above):

```yaml
main-server: "main"
```

## Send everyone back when it's up

With **server-check** on, xLimbo asks your main server for its status every few seconds, the way the multiplayer screen does. The moment it answers, everyone waiting is sent back, and told so (`server-check.up` in `lang/`):

```yaml
server-check:
  enabled: true
  address: "127.0.0.1:25566"
  interval-seconds: 5
```

`address` is the main server's own address as the xLimbo server can reach it, **not** the proxy's: with the Velocity example above, `127.0.0.1:25566`. IPv6 addresses go in brackets: `[::1]:25566`. The main server must answer server-list pings (`enable-status=true`, the default). A server only answers once it's fully started, so nobody is sent to one that's still loading. If your backends are firewalled, allow the xLimbo server to reach the main server's port.

Players who arrive while the main server is up (sent here on purpose, say) are sent back after five seconds. A player the main server keeps sending back, such as one who's banned there, is tried at most three times in five minutes, so they aren't bounced forever, and a reload doesn't reset that; `/join` still works for them.

### Pacing

Sending a whole network back in the same second can flatten a server that's just started. The queue sends a few players each second instead, and shows each one their place in line on the action bar:

```yaml
queue:
  per-second: 5
```

`per-second: 0` sends everyone at once. The line on the action bar is `queue.action-bar` in [`lang/`](/xlimbo/messages/); a vanished player ([xVanish](/xlimbo/on-one-server/#xvanish)) isn't counted in anyone else's place or total. A plugin can put some players ahead (a donor rank, say) with the API's [priority hook](/xlimbo/developer-api/#add-ons), or hold players back (a login plugin) with a hold.

## Send players back after a delay

Without server-check, players can be sent back on their own after a delay, paced by the same queue. With server-check on, it takes over: players go back as soon as the main server is up, and only `auto-join.sending` in `lang/` is used, as the message each player gets when they're sent.

```yaml
auto-join:
  enabled: true
  delay-seconds: 30
```

## Test it

With a player online, stop your main server. They should land in xLimbo with a welcome message instead of being disconnected. Start it again: with server-check on, they're sent back on their own once it's up; otherwise run `/join`.

## Securing the proxy

`/join` works by sending the proxy a "Connect" plugin message. **If forwarding isn't secured, a player's client can send that message too**, and move itself around your network. xLimbo can't prevent this; the proxy and servers must:

- **Velocity:** use [modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding) (`player-info-forwarding-mode = "modern"`) with a forwarding secret, and set it up on each backend server.
- **BungeeCord:** turn on IP forwarding, and firewall the backend servers so only the proxy can reach them.

Only set `online-mode=false` on a backend server when it sits behind a proxy with secure forwarding.
