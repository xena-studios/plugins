---
title: Commands and permissions
description: Every zChat command and permission, and their defaults.
sidebar:
  order: 5
---

Every permission defaults to `op` unless it says otherwise. Change a command's permission or aliases in the [config](/zchat/configuration/), and apply it with `/zchat reload`.

## Commands

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/clearchat` | `cc` | `zchat.command.clearchat` | Clears chat for everyone. |
| `/mutechat` | `mc` | `zchat.command.mutechat` | Turns the global chat mute on or off. |
| `/togglechat` | `tc` | `zchat.command.togglechat` (**everyone**) | Hides or shows other players' chat for yourself. |
| `/msg <player> <message>` | `tell`, `w`, `whisper`, `m`, `pm` | `zchat.command.msg` (**everyone**) | Sends a private message. Replaces Minecraft's. |
| `/reply <message>` | `r` | `zchat.command.reply` (**everyone**) | Answers your last private message. |
| `/socialspy` | `spy` | `zchat.command.socialspy` | Shows you every private message. |
| `/zchat reload` | `zc` | `zchat.admin` | Re-reads the config and applies all of it, commands included. |
| `/zchat info` | `zc` | `zchat.admin` | Shows the version, which commands are on, whether an update is out, and any config problems. |

## Bypasses

| Permission | Lets the player |
| --- | --- |
| `zchat.bypass.mute` | Talk while chat is muted. |
| `zchat.bypass.cooldown` | Ignore the cooldown. |
| `zchat.bypass.anti-repeat` | Repeat themselves. |
| `zchat.bypass.filter` | Skip the filter. |
| `zchat.bypass.clearchat` | Keep their chat on `/clearchat`. |

## Formatting and mentions

| Permission | Lets the player |
| --- | --- |
| `zchat.chat.color` | Use colour and style tags in their own messages. |
| `zchat.mention.everyone` | Ping everyone with `@everyone`. |
| `zchat.group.vip`, `zchat.group.staff`, `zchat.group.admin` | Get that group's chat format. Given to **nobody** by default, operators included. Groups you add use `zchat.group.<name>`, or the permission you give them. |

See [Chat formats](/zchat/formats/) and [Mentions and messages](/zchat/messages/).
