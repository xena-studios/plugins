---
title: Moderation
description: The chat filter, cooldown, chat mute, clearing chat, and hiding chat.
sidebar:
  order: 3
---

Each tool has an `enabled` switch, so turn off any you don't want.

## Filter

Blocks or censors words and patterns.

```yaml
filter:
  enabled: true
  mode: censor        # censor, or block
  censor-char: "*"
  patterns:
    - "badword1"
    - "badword2"
  message-blocked: "<red>Your message was blocked by the chat filter.</red>"
```

- **`censor`** masks each match with `censor-char` and lets the message through.
- **`block`** cancels the message and sends the player `message-blocked`.

Patterns ignore case. Each is a Java regular expression, so a plain word matches anywhere, even inside other words. To match only whole words, wrap it in `\\b`: `"\\bword\\b"`. Patterns are compiled when the config loads, not per message.

Players with `zchat.bypass.filter` are never filtered.

## Cooldown

Sets a minimum gap between one player's messages.

```yaml
cooldown:
  enabled: true
  seconds: 3
  message: "<red>Please wait <yellow><seconds></yellow>s before chatting again.</red>"
```

`<seconds>` is the whole seconds left. Players with `zchat.bypass.cooldown` are never held back.

## Mute chat: `/mutechat`

`/mutechat` (alias `/mc`) turns a global mute on or off. While chat is muted, only players with `zchat.bypass.mute` can talk; anyone else gets `message-blocked`. The mute lasts until someone turns it off or the server restarts.

## Clear chat: `/clearchat`

`/clearchat` (alias `/cc`) sends every player enough blank lines to push the chat out of view (`lines`, default 100), then shows `message-cleared`. Players with `zchat.bypass.clearchat` keep their chat.

## Hide chat: `/togglechat`

`/togglechat` (alias `/tc`) lets a player hide other players' chat messages for themselves, and show them again. Everyone can use it by default. Their own commands still work while chat is hidden. It resets when they rejoin.

## What runs first

For each message, zChat checks the cheapest things first: mute, then cooldown, then filter, then the format, then who's hidden chat. A message stopped early costs almost nothing.
