---
title: Network chat
description: "Chat and private messages across a Velocity or BungeeCord network's servers, with zChat on the proxy and on each server."
sidebar:
  order: 3.8
---

With zChat on the proxy too, chat on one server reaches the players on every other, and `/msg` and `/reply` reach a player on any server. It works with Velocity (3.4 or newer) and BungeeCord (or Waterfall) in front of Paper, Folia, Spigot and CraftBukkit servers.

## Turning it on

1. Put zChat's proxy jar on the proxy (`zchat-<version>-velocity.jar` or `-bungeecord.jar`), and zChat on each server.
2. On each server, turn it on in `plugins/zChat/config.yml`, then restart:

   ```yaml
   network:
     enabled: true
   ```

3. Give the network its secret, the same on the proxy and every server: `plugins/Xena/proxy.secret`. On Velocity with modern forwarding there's nothing to set: zChat uses the forwarding secret the proxy and each Paper server already share. A BungeeCord proxy that finds none writes a new one to its `plugins/Xena/proxy.secret` and says to copy it to each server.

The proxy's command is `/zchatproxy` (alias `/zcp`), with `reload` and `debug`. It isn't `/zchat`, so `/zchat reload` still reaches the server you're on.

## What crosses, and what doesn't

| | |
| --- | --- |
| **Chat** (`network.chat`) | Each server draws its own players' lines in its own formats, then sends that line to the others, which show it with `network.server-format` in front (`[lobby] `). So a rank's format and its placeholders are the sender's server's. |
| **Private messages** (`network.private-messages`) | `/msg <player>` completes and finds players on every server. The recipient's server delivers it in its own format, with its sound; the sender sees their line once it arrived, or "No player called ..." if it didn't. `/reply` answers across servers. Social spies see the private messages sent or received on their own server. |
| **The filter, the mute, mute providers** | Applied on the sender's server before the line leaves it, so a muted or filtered message never reaches the others. Keep the filter the same on every server; a punishment plugin that mutes network-wide (xBans) mutes everywhere through zChat's [API](/zchat/developer-api/#mutes). |
| **`/mutechat`, `/clearchat`** | Each server's own. |
| **Hidden chat, mentions** | The receiving server's: a player who hid chat doesn't see other servers' lines, and a player mentioned by name on another server hears the ping. |

## Limits

- **A server with nobody on it** gets nothing, and sends nothing: plugin messages ride a player's connection. That's fine for chat, which nobody would see.
- **Not on Fabric, NeoForge or Forge yet.** The proxy layer has no end on them, so such a server's chat stays on it, and the console says so if `network.enabled` is on.
- **Signed chat.** A line from another server arrives as a system message, unsigned.
