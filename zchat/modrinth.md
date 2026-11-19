![zChat by Xena Studios. A green pixel chat bubble icon and the zChat name over a stepped horizon in green, with the headline "Format your chat. Keep it clean."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/zchat/banner.png)

## Format your chat. Keep it clean.

Every rank wants its own chat style. Someone always finds a way to spell the word you blocked, and someone else sends the same message ten times. **zChat gives every rank its format, catches the words people try to sneak past, and keeps spam in check**, in one small chat plugin built to run on every message without costing you anything.

**[Read the docs](https://plugins.xenastudios.co/zchat/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **You won't feel it running, even in busy chat.** Formats and the filter are prepared once when the config loads, the cheapest checks run first, and each message is drawn once, not once for every player who sees it. Nothing runs on a timer but the daily update check and bStats, and you can turn both off.
- **A filter that's hard to dodge.** It catches `s p a c e d` words, `b4dw0rd`, `baaadword` and `bädword`, but leaves innocent words like *class* alone.
- **Players can't break your chat.** What players type is shown as plain text, so nobody can sneak in colours, fake links or hover text. Give them the colour permission and they get colours and styles, never clicks or hovers.
- **Works with what you have.** Ranks are picked by permission, so any permissions plugin works, like LuckPerms. PlaceholderAPI placeholders work in your formats when it's installed.
- **Every change applies on reload**, even turning commands on or off. No restarts.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia.

---

## What it does

**Chat formats per rank.** VIP, Staff, Admin, or any rank you add, each with its own style. Players get the highest-weight rank they have, and everyone else gets your default.

    formatting:
      groups:
        vip:
          weight: 10
          permission: "zchat.group.vip"
          format: "<gold>[VIP] <player></gold> <dark_gray>»</dark_gray> <white><message></white>"

Full MiniMessage, so you can use colours, gradients, hover text and links, with `<player>`, `<displayname>`, `<message>` and any PlaceholderAPI placeholder.

**A chat filter.** Censor or block the words you list, however they're spelled, and any regex patterns you add. It covers private messages too.

**Anti-spam.** A cooldown between messages, three seconds by default, and anti-repeat, which stops the same message being sent again and again, even with the case or spacing changed.

**Mentions.** Write a player's name in chat, with or without `@`, and it's highlighted and they hear a ping. Operators can `@everyone`, or anyone you give the permission.

**Private messages.** `/msg` and `/reply` for everyone, replacing Minecraft's `/msg`, `/tell` and `/w`.

Every feature can be turned off and reworded, and every command renamed and given its own permission.

---

## What your staff get

- `/filter add <word>`: when someone finds a new word to dodge with, add it in game. It applies at once.
- `/mutechat` (or `/mc`): mute chat for everyone without `zchat.bypass.mute`.
- `/clearchat` (or `/cc`): wipe chat from every player's screen.
- `/socialspy` (or `/spy`): see every private message.
- Bypass permissions for the mute, the cooldown, anti-repeat and the filter.

And every player gets `/togglechat` (or `/tc`) to hide chat for themselves. Staff commands are op-only until you give them out.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server.
2. Style your ranks in `plugins/zChat/config.yml`, add your filter words, and run `/zchat reload`.
3. Give your ranks their `zchat.group.<rank>` permission, and your staff `zchat.command.mutechat`, `zchat.command.clearchat` and `zchat.command.filter`.

**Coming from zChat 2?** Replace the jar and restart. Your config is carried forward, and the old file is kept as `config.yml.bak-v1`. The `zchat.group.*` permissions no longer go to operators by default, so give your ranks theirs. A zChat 1.x config can't be carried forward; zChat keeps it as `config.yml.old` and writes a fresh one.

Full setup, every setting and every permission: **[plugins.xenastudios.co/zchat](https://plugins.xenastudios.co/zchat/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. PlaceholderAPI is optional.

---

## More from Xena Studios

<!-- catalog:start: the other released plugins, from the brand catalog (pnpm catalog in docs/). -->
- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, every feature free, with CoreProtect's commands and your CoreProtect history.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute. Select an area and every plot and sign is made for you; players rent, add members and pay ahead.
<!-- catalog:end -->

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/zchat)
