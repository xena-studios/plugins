---
title: Chat formats
description: Give each rank its own chat format, with placeholders and PlaceholderAPI.
sidebar:
  order: 2
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

Group permissions are given to nobody by default, operators included, so an operator doesn't get the admin format unless you give it to them. (zChat 2 gave them to operators.)

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
| `<message>` | What they typed, after the [filter](/zchat/moderation/#filter), with [mentions](/zchat/messages/#mentions) highlighted. |
| `%...%` | Any [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) placeholder, such as `%vault_prefix%` or `%luckperms_prefix%`, when PlaceholderAPI is installed. Without it, they show nothing, and the console says so. |

Formats can also use the [theme](/start/formatting/#the-theme)'s `<highlight>`, `<success>` and `<error>`.

PlaceholderAPI values go into the format as text with their colours (`&` codes, or MiniMessage colour tags), after the format is read. So a placeholder that shows something a player chose, such as a nickname, can't add a clickable link or hover text to chat. A placeholder whose expansion breaks shows nothing rather than breaking chat.

## Letting players use colours

Players holding the `color-permission` (default `zchat.chat.color`, `op` by default) can use MiniMessage colour and style tags in their own messages, such as `<red>`, `<bold>` or `<gradient:gold:red>`. Only colours and styles work: never clicks, hovers or anything else. Set `color-permission: ""` so nobody can.
