---
title: Mentions and messages
description: Mentions with a ping, @everyone, private messages, /reply and social spy.
sidebar:
  order: 4
---

## Mentions

Writing a player's name in chat, with or without `@`, highlights it for everyone and plays a ping to that player.

```yaml
mentions:
  enabled: true
  format: "<highlight>@<name></highlight>"
  sound: "entity.experience_orb.pickup 0.5 1.2"
  everyone-permission: "zchat.mention.everyone"
```

- **`format`** is how a mention looks. `<name>` is the player's name as they spell it.
- **`sound`** is a Minecraft sound name, then optionally a volume (0 to 10) and pitch (0.5 to 2). It plays in the player's Players volume, so they can turn it down. Blank for none.
- **`@everyone`** pings everyone who can see the message, for players with `everyone-permission` (operators by default).

Players aren't pinged by their own name, by someone who can't see them (so vanished staff stay hidden), or while they've [hidden chat](/zchat/moderation/#hide-chat-togglechat).

## Private messages

| Command | Aliases | Permission |
| --- | --- | --- |
| `/msg <player> <message>` | `tell`, `w`, `whisper`, `m`, `pm` | `zchat.command.msg` (**everyone**) |
| `/reply <message>` | `r` | `zchat.command.reply` (**everyone**) |
| `/socialspy` | `spy` | `zchat.command.socialspy` |

`/msg` replaces Minecraft's `/msg`, `/tell` and `/w`, and gives them back if you turn it off. Like Minecraft's, it takes selectors such as `@p` or `@a[distance=..10]`, on Paper; Folia can't safely look across regions for them. `/reply` answers whoever you last messaged, or who last messaged you, the console included. Command blocks can send private messages but can't be replied to.

```yaml
private-messages:
  msg:
    format-sent: "<highlight>You → <player></highlight><dark_gray>:</dark_gray> <white><message>"
    format-received: "<highlight><player> → You</highlight><dark_gray>:</dark_gray> <white><message>"
    sound: "entity.experience_orb.pickup 0.5 1.2"
```

`<player>` is the other player and `<message>` what was said. `sound` plays to whoever receives a message.

The [filter](/zchat/moderation/#filter) applies to private messages. The chat mute, cooldown and anti-repeat don't, and players who've hidden chat still get them. Players with the colour permission can use colours in them, as in chat. Players you can't see, such as vanished staff, count as offline.

## Social spy

`/socialspy` shows a player every private message between other players:

```yaml
  socialspy:
    remember: true
    log-to-console: true
    format: "<dark_gray>[Spy]</dark_gray> <gray><sender> → <receiver>: <message>"
```

With `remember: true`, it stays on when they rejoin, as long as they still have the permission. With `log-to-console: true`, every private message is also written to the console log, for moderation.
