---
title: Commands and permissions
description: Every zChat command and permission, and their defaults.
sidebar:
  order: 4
---

Every permission defaults to `op` unless it says **everyone**. Change a command's permission or aliases in the [config](/zchat/configuration/) (restart to apply).

## Commands

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/clearchat` | `cc` | `zchat.command.clearchat` | Clears chat for everyone. |
| `/mutechat` | `mc` | `zchat.command.mutechat` | Turns the global chat mute on or off. |
| `/togglechat` | `tc` | `zchat.command.togglechat` (**everyone**) | Hides or shows chat for yourself. |
| `/zchat reload` | `zc` | `zchat.admin` | Re-reads the config and applies it. |
| `/zchat info` | `zc` | `zchat.admin` | Shows the version and status. |

## Bypasses

| Permission | Lets the player |
| --- | --- |
| `zchat.bypass.mute` | Talk while chat is muted. |
| `zchat.bypass.cooldown` | Ignore the cooldown. |
| `zchat.bypass.filter` | Skip the filter. |
| `zchat.bypass.clearchat` | Keep their chat on `/clearchat`. |

## Formatting

| Permission | Lets the player |
| --- | --- |
| `zchat.chat.color` | Use colour and style tags in their own messages. |
| `zchat.group.vip`, `zchat.group.staff`, `zchat.group.admin` | Get that group's chat format. Groups you add use whatever permission you give them. |

See [Chat formats](/zchat/formats/).
