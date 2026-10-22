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

Combine them freely. A clickable, underlined link with a tooltip, as xUtilities' link commands use (`<prefix>` and `<highlight>` come from [the theme](#the-theme)):

```yaml
message: "<prefix>Join our Discord: <click:open_url:'https://discord.gg/example'><hover:show_text:'<gray>Click to open'><highlight><underlined>discord.gg/example"
```

## The theme

Every plugin's config starts with a `theme`: the look its messages share.

```yaml
theme:
  prefix: "<#B48CFF>»</#B48CFF> "
  base: "<gray>"
  highlight: "<#B48CFF>"
  success: "<#5EE08A>"
  error: "<#FF6B6B>"
```

| In a message | What it is |
| --- | --- |
| `<prefix>` | The `prefix` text. The default messages start with it, so setting it to your server's name brands every message at once. Blank for none. |
| `<highlight>...</highlight>` | Names and values, such as a player or a number. |
| `<success>...</success>` | Something turned on or done. |
| `<error>...</error>` | Something refused or turned off. |

`base` is the colour of any text with no tag of its own, so it needs no tag. A style is MiniMessage tags (`<gold><bold>`) or bare names (`gold bold`). Gradients can't be a style, but they can go in the prefix.

The default messages use only these, so a new look takes five lines. Messages you write can use them too, or set any colour directly.

## Placeholders

Some messages take placeholders, also in angle brackets: `<player>`, `<seconds>`, `<server>` and so on. Each plugin's configuration page lists which placeholders each message accepts.

## Test a message

Adventure's [MiniMessage viewer](https://webui.advntr.dev/) shows what a message will look like before you put it in a config.
