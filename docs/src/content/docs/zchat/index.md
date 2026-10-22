---
title: zChat
description: Format your chat. Keep it clean. Chat formatting, moderation and private messages for Paper and Folia.
sidebar:
  label: Overview
  order: 1
---

**Format your chat. Keep it clean.**

zChat handles the chat basics almost every server wants: a chat format per rank, a filter that's hard to dodge, a cooldown and anti-spam, mentions, private messages, a global chat mute, clearing chat, and letting players hide chat for themselves. Every piece can be turned off.

- **Built for the hot path.** zChat runs on every chat message, so that path is kept cheap: formats and the filter are compiled when the config loads, the cheapest checks run first, and each message is drawn once, not once per player who sees it. No repeating tasks.
- **A filter that's hard to dodge.** It catches `s p a c e d` words, `b4dw0rd`, `baaadword` and `bädword`, but leaves innocent words like *class* alone.
- **Players can't inject formatting.** What a player types goes into the format as plain text, so they can't add colours, links or hover text, unless you give them the colour permission (and even then, only colours and styles). PlaceholderAPI values can't add them either.
- **Every change applies on reload**, even turning commands on or off. No restarts.
- **Works with any permissions plugin**, because ranks are picked by permission. PlaceholderAPI is optional.
- **Paper and Folia**, from the same jar.

Current version: **3.0.0**, for Paper 1.21.11 or newer and Java 21. [Download on Modrinth](https://modrinth.com/plugin/zchat).

:::note[Coming from zChat 2?]
Replace the jar and restart. Your config is carried forward to zChat 3's format, and the old file is kept beside it as `config.yml.bak-v1`. zChat 3 needs Paper 1.21.11 or newer. One change to check: the `zchat.group.*` permissions no longer default to operators, so give your ranks their group permission (see [Chat formats](/zchat/formats/#which-format-a-player-gets)).
:::

:::note[Coming from zChat 1.x?]
zChat 1.x's config can't be carried forward. zChat 3 keeps it as `config.yml.old` and writes a fresh config: copy your formats and words across by hand.
:::

## Start here

1. [Install it](/start/installing/).
2. Set up a format for each rank: [Chat formats](/zchat/formats/).
3. Set up the filter, cooldown and anti-spam: [Moderation](/zchat/moderation/).
4. Mentions and private messages work out of the box: [Mentions and messages](/zchat/messages/).
5. Give your staff their permissions: [Commands and permissions](/zchat/commands/).
