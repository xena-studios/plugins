![zChat by Xena Studios. A pixel chat bubble icon and the zChat name over a stepped horizon in green, with the headline "Format your chat. Keep it clean."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/zchat/banner.png)

## Format your chat. Keep it clean.

Give every rank its own chat style, stop the words you don't want, and keep spam in check. **zChat does the chat basics every server needs**, in one small plugin built to run on every single message without costing you a thing.

**[Read the docs](https://docs.xenastudios.co/zchat/)** · **[Chat formats](https://docs.xenastudios.co/zchat/formats/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **A format for every rank.** VIP, Staff, Admin, or any rank you add, each with its own style. Players get the highest rank they have, and everyone else gets your default.
- **No lag, even in busy chat.** Formats and the filter are prepared once when the config loads, the cheapest checks run first, and each message is drawn once, not once for every player who sees it.
- **A filter that's hard to dodge.** It catches `s p a c e d` words, `b4dw0rd`, `baaadword` and `bädword`, but leaves innocent words like *class* alone.
- **Players can't break your chat.** What players type is always shown as plain text, so nobody can sneak in colours, fake links or hover text, unless you give them the colour permission.
- **Works with what you have.** Ranks are picked by permission, so any permissions plugin works, like LuckPerms. PlaceholderAPI placeholders work in your formats when it's installed.
- **Every change applies on reload**, even turning commands on or off. No restarts.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia.

---

## What you get

**Chat formats per rank**

    formatting:
      groups:
        vip:
          weight: 10
          permission: "zchat.group.vip"
          format: "<gold>[VIP] <player></gold> <dark_gray>»</dark_gray> <white><message></white>"

Full MiniMessage, so you can use colours, gradients, hover text and links. Use `<player>`, `<displayname>`, `<message>` and any PlaceholderAPI placeholder.

**A chat filter**
Block or censor the words and patterns you list, however they're spelled. Staff can bypass it.

**Anti-spam**
A cooldown between messages, three seconds by default, and anti-repeat, which stops the same message being sent again and again.

**Mentions**
Write a player's name in chat, with or without `@`, and it's highlighted and they hear a ping. Operators can `@everyone`, or anyone you give the permission.

**Private messages**
`/msg` and `/reply`, with `/socialspy` so staff can see them.

**Moderation commands**
- `/mutechat` (or `/mc`): mute chat for everyone except staff.
- `/clearchat` (or `/cc`): wipe chat from every player's screen.
- `/togglechat` (or `/tc`): lets any player hide chat for themselves.

Every feature and command can be turned off, renamed, given its own permission and reworded.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server.
2. Style your ranks in `plugins/zChat/config.yml` and add your filter words.
3. Give your ranks their `zchat.group.<rank>` permission and your staff the moderation permissions.

Full setup, every setting and every permission: **[docs.xenastudios.co/zchat](https://docs.xenastudios.co/zchat/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. PlaceholderAPI is optional.

**Coming from zChat 2?** Replace the jar and restart. Your config is carried forward, and the old file is kept as `config.yml.bak-v1`. The `zchat.group.*` permissions no longer go to operators by default, so give your ranks theirs. A zChat 1.x config can't be carried forward; zChat keeps it as `config.yml.old` and writes a fresh one.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/zchat)
