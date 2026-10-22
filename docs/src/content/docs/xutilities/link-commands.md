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
    message: "<prefix>Join our Discord: <click:open_url:'https://discord.gg/yourserver'><hover:show_text:'<gray>Click to open'><highlight><underlined>discord.gg/yourserver"
```

## Add one

To add `/vote`, add a `vote` entry. Only `message` is needed:

```yaml
info-commands:
  # ...discord, store, website...
  vote:
    aliases: ["votes"]
    message: "<prefix>Vote for us: <click:open_url:'https://example.com/vote'><highlight><underlined>example.com/vote"
```

Then run `/xu reload`. Adding, removing or renaming a link command, and changing anything about it, applies on reload.

## More than one line

Use `<newline>` in the message, or a YAML block, where each line of the block is a line in chat:

```yaml
  rules:
    message: |-
      <highlight>Server rules</highlight>
      1. Be kind.
      2. No griefing.
```

## Settings

| Key | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | `false` removes the command. |
| `aliases` | none | Other names for it, such as `["votes"]`. |
| `permission` | `xutilities.command.<name>` | Who can use it. The default is everyone's. Blank (`""`) means no restriction. |
| `message` | | What the player sees, in [MiniMessage](/start/formatting/). It can use the [theme](/xutilities/configuration/#make-it-look-like-your-server)'s tags, like `<prefix>` and `<highlight>`. |

A link command whose name or alias clashes with one of xUtilities' own commands is skipped, with a warning in the console.
