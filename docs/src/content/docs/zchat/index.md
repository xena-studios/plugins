---
title: zChat
description: Format your chat. Keep it clean. Chat formatting and moderation for Paper and Folia.
sidebar:
  label: Overview
  order: 1
---

**Format your chat. Keep it clean.**

zChat handles the chat basics almost every server wants: a chat format per rank, a word filter, a cooldown between messages, a global chat mute, clearing chat, and letting players hide chat for themselves. Every piece can be turned off.

- **Built for the hot path.** zChat runs on every chat message, so that path is kept cheap: formats are parsed and filter patterns compiled when the config loads, and each message is rendered once, not once per player who sees it. No repeating tasks.
- **Players can't inject formatting.** What a player types goes into the format as plain text, so they can't add colours, links or hover text, unless you give them the colour permission (and even then, only colours and styles).
- **Works with any permissions plugin**, because ranks are picked by permission. PlaceholderAPI is optional.
- **Paper and Folia**, from the same jar.

Current version: **2.0.0**, for Paper 1.20.6 or newer and Java 21. [Download on Modrinth](https://modrinth.com/plugin/zchat).

:::note[Coming from zChat 1.x?]
zChat 1.x ran on Spigot and Minecraft 1.13 to 1.20.6. zChat 2 needs Paper (or Purpur or Folia) 1.20.6 or newer. Back up your old config before you upgrade, then check your settings against zChat 2's [default config](/zchat/configuration/#the-full-default-config).
:::

## Start here

1. [Install it](/start/installing/).
2. Set up a format for each rank: [Chat formats](/zchat/formats/).
3. Set up the filter, cooldown and mute: [Moderation](/zchat/moderation/).
4. Give your staff their permissions: [Commands and permissions](/zchat/commands/).
