---
title: Formatting text
description: Every message is MiniMessage. The tags you'll use most.
sidebar:
  order: 5
---

Every player-facing message in every plugin is written in [MiniMessage](https://docs.advntr.dev/minimessage/format.html), the text format Paper uses. Tags go in angle brackets and close with a slash.

| You write | You get |
| --- | --- |
| `<red>Hello</red>` | red text |
| `<bold>Hello</bold>` | bold text |
| `<gradient:gold:red>Hello</gradient>` | a gradient |
| `<#5EE08A>Hello` | any hex colour |
| `<click:open_url:'https://example.com'>Click me</click>` | a clickable link |
| `<hover:show_text:'More info'>Hover me</hover>` | a tooltip |

Combine them freely. A clickable, underlined link with a tooltip, as xUtilities' link commands use:

```yaml
message: "<blue>Join our Discord: <click:open_url:'https://discord.gg/example'><hover:show_text:'<gray>Click to open'><underlined>discord.gg/example</underlined></hover></click></blue>"
```

## Placeholders

Some messages take placeholders, also in angle brackets: `<player>`, `<seconds>`, `<server>` and so on. Each plugin's configuration page lists which placeholders each message accepts.

## Test a message

Adventure's [MiniMessage viewer](https://webui.advntr.dev/) shows what a message will look like before you put it in a config.
