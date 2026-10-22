---
title: Moderation
description: The chat filter, cooldown, anti-repeat, chat mute, clearing chat, and hiding chat.
sidebar:
  order: 3
---

Each tool has an `enabled` switch, so turn off any you don't want.

## Filter

Censors or blocks words, in chat and in [private messages](/zchat/messages/#private-messages).

```yaml
filter:
  enabled: true
  mode: "censor"      # censor, or block
  censor-char: "*"
  smart: true
  words: ["badword1", "badword2"]
  patterns: []
  message-blocked: "<prefix><error>Your message was blocked by the chat filter."
```

- **`censor`** replaces what matched with `censor-char` and lets the message through.
- **`block`** stops the message and sends the player `message-blocked`.

### Words

`words` are matched as **whole words**, ignoring case, so `ass` catches *ass* but leaves *class* and *assassin* alone. Put `*` at either end to also catch longer words: `bad*` catches *badly*.

With **`smart: true`** (the default), the filter also sees through the usual tricks:

| Typed | Caught as |
| --- | --- |
| `b a d w o r d`, `b.a.d.w.o.r.d` | spaced or dotted out |
| `b4dw0rd`, `b@dw0rd` | numbers and symbols for letters (`4`→a, `0`→o, `1`→i, `3`→e, `5`→s, `7`→t, `@`→a, `$`→s) |
| `baaaadword` | stretched letters |
| `bädwörd`, `ｂａｄｗｏｒｄ` | accents, full-width and other look-alike letters |
| `bad​word` | invisible characters, such as zero-width spaces |
| `you're a b a d w o r d` | a spaced-out word, even after a one-letter word |

It censors exactly what the player typed, so `b a d w o r d` becomes `* * * * * * *`. Spaced-out digits alone, like `4 5 5`, are left as numbers. Turn `smart` off to match words only as typed.

Players with the [colour permission](/zchat/formats/#letting-players-use-colours) are filtered on their words with the tags taken out, so `bad<reset>word` is still caught. A censored message loses its colours.

### Patterns

For anything words can't express, `patterns` are Java regular expressions, matched anywhere in the message as typed, ignoring case: `"free\\s*money"`. They aren't smart. A pattern that isn't a valid regular expression is skipped, with a warning in the console.

Words and patterns are compiled when the config loads, not per message. Players with `zchat.bypass.filter` are never filtered.

:::note[Secure chat]
Players who turn on Minecraft's **Only Show Secure Chat** see messages as the player signed them, before zChat changed them, so they see censored words uncensored. `block` mode isn't affected. This is how Minecraft's chat signing works, for every chat plugin.
:::

## Cooldown

Sets a minimum gap between one player's messages.

```yaml
cooldown:
  enabled: true
  seconds: 3
  message: "<prefix><error>Wait <highlight><seconds>s</highlight> before chatting again."
```

`seconds` can have decimals, such as `1.5`. `<seconds>` is the whole seconds left. Players with `zchat.bypass.cooldown` are never held back.

## Anti-repeat

Stops a player sending the same message again soon after, the spam a cooldown alone misses.

```yaml
anti-repeat:
  enabled: true
  seconds: 30
  similarity: 90
  message: "<prefix><error>You just said that."
```

A message counts as a repeat if it's at least `similarity` percent like the player's last one within `seconds`. Messages are compared the way the smart filter sees them, so changing the case, spacing or punctuation doesn't make a message new. `100` catches only exact repeats. Messages under three letters, like *gg* or *ok*, are always let through. Players with `zchat.bypass.anti-repeat` are never stopped.

## Mute chat: `/mutechat`

`/mutechat` (alias `/mc`) turns a global mute on or off. While chat is muted, only players with `zchat.bypass.mute` can talk; anyone else gets `message-blocked`. Private messages still work. The mute lasts through `/zchat reload`, until someone turns it off or the server restarts.

## Clear chat: `/clearchat`

`/clearchat` (alias `/cc`) pushes everyone's chat out of view with `lines` blank lines (default 100), sent as one message, then shows `message-cleared`. Players with `zchat.bypass.clearchat` keep their chat.

## Hide chat: `/togglechat`

`/togglechat` (alias `/tc`) lets a player hide other players' chat messages for themselves, and show them again. Everyone can use it by default. Their own messages, private messages and commands still work, and they aren't pinged by [mentions](/zchat/messages/#mentions).

With `remember: true` (the default), the choice is kept when they rejoin. It's stored on the player, so there's no file to manage.

## What runs first

For each message, zChat checks the cheapest things first: mute, then cooldown, then filter, then anti-repeat, then mentions and the format. A message stopped early costs almost nothing, and one that gets through is drawn once for everyone.
