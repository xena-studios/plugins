---
title: Link commands
description: Make your own /discord, /store, /vote or /rules commands.
sidebar:
  order: 3
---

Link commands are commands you define in the config, with no code: each one sends the player a message, usually a clickable link. xUtilities ships three examples, `/discord`, `/store` and `/website`, pointing at `example.com` addresses. **Replace those URLs** before you go live.

They live under `info-commands` in `plugins/xUtilities/config.yml`. Each key becomes a command:

```yaml
info-commands:
  discord:
    enabled: true
    aliases: []
    permission: "xutilities.command.discord"
    message: "<blue>Join our Discord: <click:open_url:'https://discord.gg/yourserver'><hover:show_text:'<gray>Click to open'><underlined>discord.gg/yourserver</underlined></hover></click></blue>"
```

## Add one

To add `/vote`, add a `vote` entry:

```yaml
info-commands:
  # ...discord, store, website...
  vote:
    enabled: true
    aliases: ["votes"]
    message: "<green>Vote for us: <click:open_url:'https://example.com/vote'><underlined>example.com/vote</underlined></click></green>"
```

Then **restart the server**: adding, removing or renaming a link command, or changing its aliases or permission, only takes effect at startup. Changing just the `message` applies on `/xutilities reload`.

## Settings

| Key | What it does |
| --- | --- |
| `enabled` | `false` removes the command. |
| `aliases` | Other names for it, such as `["votes"]`. |
| `permission` | Optional. Defaults to `xutilities.command.<name>`, which everyone has. Blank (`""`) means no restriction. |
| `message` | What the player sees, in [MiniMessage](/start/formatting/). |

A link command whose name or alias clashes with one of xUtilities' own commands is skipped, with a warning in the console.
