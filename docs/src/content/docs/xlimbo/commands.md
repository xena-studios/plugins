---
title: Commands and permissions
description: xLimbo's commands and permissions.
sidebar:
  order: 5
---

| Command | Aliases | Permission | Default | What it does |
| --- | --- | --- | --- | --- |
| `/join` | `server`, `lobby` | `xlimbo.join` | everyone | Sends you to the [main server](/xlimbo/network-setup/#point-join-at-your-main-server). |
| `/xlimbo reload` | | `xlimbo.admin` | op | Re-reads the config and applies it. |
| `/xlimbo info` | | `xlimbo.admin` | op | Shows the version and status. |

`/join` has a cooldown of `cooldown-seconds` (default 3) per player, so nobody can spam your proxy. Its extra aliases are set in the config; `/join` itself always exists.

```yaml
join-command:
  enabled: true
  aliases:
    - "server"
    - "lobby"
  permission: "xlimbo.join"
  cooldown-seconds: 3
  message-sending: "<green>Connecting you to <aqua><server></aqua>...</green>"
  message-cooldown: "<red>Please wait <yellow><seconds></yellow>s before trying again.</red>"
```

Changing the aliases needs a restart.
