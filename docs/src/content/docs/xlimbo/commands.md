---
title: Commands and permissions
description: xLimbo's commands and permissions.
sidebar:
  order: 5
---

| Command | Aliases | Permission | Default | What it does |
| --- | --- | --- | --- | --- |
| `/join` | `server`, `lobby` | `xlimbo.join` | everyone | Sends you to the [main server](/xlimbo/network-setup/#point-join-at-your-main-server). |
| `/xlimbo reload` | | `xlimbo.admin` | op | Re-reads the config and applies all of it, the world included. |
| `/xlimbo info` | | `xlimbo.admin` | op | Shows the version, which commands are on, whether an update is out, and any config problems. |

`/join` has a cooldown of `cooldown-seconds` (default 3) per player, so nobody can spam your proxy. A player who uses it leaves the [return queue](/xlimbo/network-setup/#pacing). `/join` can be turned off with `enabled: false`.

```yaml
join-command:
  enabled: true
  aliases:
    - "server"
    - "lobby"
  permission: "xlimbo.join"
  cooldown-seconds: 3
  message-sending: "<prefix>Connecting you to <highlight><server></highlight>..."
  message-cooldown: "<prefix><error>Wait <highlight><seconds>s</highlight> before trying again."
```

Every change applies with `/xlimbo reload`, aliases included.
