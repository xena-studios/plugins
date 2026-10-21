---
title: Network setup
description: Make xLimbo your proxy's fallback, and connect /join to your main server.
sidebar:
  order: 2
---

xLimbo runs on its **own** Paper server behind your proxy, next to your main server. Two things connect it:

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

Players can also be sent back on their own after a delay:

```yaml
auto-join:
  enabled: true
  delay-seconds: 30
  message: "<green>Sending you to the server...</green>"
```

## Test it

With a player online, stop your main server. They should land in xLimbo with a welcome message instead of being disconnected. Start it again and run `/join`.

## Securing the proxy

`/join` works by sending the proxy a "Connect" plugin message. **If forwarding isn't secured, a player's client can send that message too**, and move itself around your network. xLimbo can't prevent this; the proxy and servers must:

- **Velocity:** use [modern forwarding](https://docs.papermc.io/velocity/player-information-forwarding) (`player-info-forwarding-mode = "modern"`) with a forwarding secret, and set it up on each backend server.
- **BungeeCord:** turn on IP forwarding, and firewall the backend servers so only the proxy can reach them.

Only set `online-mode=false` on a backend server when it sits behind a proxy with secure forwarding.
