---
title: Chat formats
description: Give each rank its own chat format, with placeholders and PlaceholderAPI.
sidebar:
  order: 3
---

Each chat message is shown in a **format**: a [MiniMessage](/start/formatting/) template with the sender's name and message in it. You set a format per **group**, and each group is tied to a permission.

```yaml
formatting:
  enabled: true
  color-permission: "zchat.chat.color"
  default-format: "<gray><player>:</gray> <white><message></white>"
  groups:
    vip:
      weight: 10
      permission: "zchat.group.vip"
      format: "<gold>[VIP] <player></gold> <dark_gray>»</dark_gray> <white><message></white>"
    staff:
      weight: 20
      permission: "zchat.group.staff"
      format: "<aqua>[Staff] <player></aqua> <dark_gray>»</dark_gray> <white><message></white>"
    admin:
      weight: 30
      permission: "zchat.group.admin"
      format: "<red>[Admin] <player></red> <dark_gray>»</dark_gray> <white><message></white>"
```

## Which format a player gets

zChat uses the **highest-weight** group the player has the permission for. A player with both `zchat.group.vip` and `zchat.group.staff` gets the staff format (weight 20 beats 10). A player in no group gets `default-format`.

So give your ranks their group permission in your permissions plugin, for example with LuckPerms:

```
/lp group staff permission set zchat.group.staff true
```

Group permissions are given to nobody by default, operators included, so an operator doesn't get the admin format unless you give it to them. On Fabric, LuckPerms and other permission mods answer through fabric-permissions-api.

## Add a group

Add an entry under `groups` with a `weight` and a `format`, then run `/zchat reload`. Its permission is `zchat.group.<name>` unless you set one:

```yaml
    builder:
      weight: 15
      format: "<green>[Builder] <player></green> <dark_gray>»</dark_gray> <white><message></white>"
```

To use only one format for everyone, leave `groups` empty (`groups: {}`). To let the server or another plugin draw chat, set `enabled: false`; the filter, cooldown and the rest still work.

## Placeholders

| Placeholder | What it is |
| --- | --- |
| `<player>` | The sender's name, as plain text. |
| `<displayname>` | The sender's display name, with any colours a nickname plugin gave it. Clicks and hovers are taken out. |
| `<message>` | What they typed, after the [filter](/zchat/moderation/#filter), with [mentions](/zchat/mentions/#mentions) highlighted. |
| `%...%` | Any [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) placeholder, such as `%vault_prefix%` or `%luckperms_prefix%`, when PlaceholderAPI is installed (Bukkit). Without it, and on Fabric for now, they show nothing, and the console says so. |
| `<name>` | A tag another plugin gives formats through zChat's [API](/zchat/developer-api/#placeholders), such as a balance. |

Formats can also use the theme's `<prefix>`, `<highlight>`, `<success>` and `<error>` (see [Configuration](/zchat/configuration/#make-it-look-like-your-server)). Formats are the server's look, so they stay in `config.yml`, the same for every reader; zChat's own messages are in `lang/` ([Messages](/zchat/messages/)).

PlaceholderAPI values go into the format as text with their colours (`&` codes, or MiniMessage colour tags), after the format is read. So a placeholder that shows something a player chose, such as a nickname, can't add a clickable link or hover text to chat. A placeholder whose expansion breaks shows nothing rather than breaking chat.

## Letting players use colours

Players holding the `color-permission` (default `zchat.chat.color`, `op` by default) can use MiniMessage colour and style tags in their own messages, such as `<red>`, `<bold>` or `<gradient:gold:red>`. Only colours and styles work: never clicks, hovers, line breaks or anything else. Set `color-permission: ""` so nobody can. Without the permission, what a player types is shown exactly as typed, tags and all.

## Drawn once

Each message is drawn once, the same line for every viewer and the console, after every other chat plugin has had its say, and that one line is sent to everyone who sees it. On Paper and Folia it's Paper's chat renderer; on Spigot the event's own format, or one line sent to each viewer when it has colours a format string can't hold; on Fabric a system message. How each platform handles Minecraft's chat signing is on [Where it runs](/zchat/#where-it-runs).
