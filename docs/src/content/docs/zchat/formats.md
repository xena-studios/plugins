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

The `zchat.group.*` permissions default to `op`, so operators see the admin format until you set this up.

## Add a group

Add an entry under `groups` with a `weight`, a `permission` and a `format`, then run `/zchat reload`:

```yaml
    builder:
      weight: 15
      permission: "zchat.group.builder"
      format: "<green>[Builder] <player></green> <dark_gray>»</dark_gray> <white><message></white>"
```

To use only one format for everyone, leave `groups` empty (`groups: {}`).

## Placeholders

| Placeholder | What it is |
| --- | --- |
| `<player>` | The sender's name, as plain text. |
| `<displayname>` | The sender's display name, which may already have colours (from a nickname plugin, for example). |
| `<message>` | What they typed, after the [filter](/zchat/moderation/#filter). |
| `%...%` | Any [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) placeholder, such as `%vault_prefix%`, when PlaceholderAPI is installed. Without it, they aren't filled in. |

## Letting players use colours

Players holding the `color-permission` (default `zchat.chat.color`, `op` by default) can use MiniMessage colour and style tags in their own messages, such as `<red>` or `<bold>`. Only colours and styles work: never clicks, hovers or anything else. Set `color-permission: ""` so nobody can.
