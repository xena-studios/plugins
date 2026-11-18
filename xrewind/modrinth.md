![xRewind by Xena Studios. A teal pixel rewind icon and the xRewind name over a stepped horizon in teal, with the headline "Every change, logged. Any of it, undone."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xrewind/banner.png)

## Every change, logged. Any of it, undone.

Someone griefed spawn. Someone emptied the shared chest. Someone says they didn't. **xRewind shows you who placed, broke or took what, where and when, and puts it back.** It's a block logger with rollback, and a free CoreProtect alternative that brings your CoreProtect history with it.

**[Read the docs](https://docs.xenastudios.co/xrewind/)** · **[Switching from CoreProtect](https://docs.xenastudios.co/xrewind/switching/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Every feature free.** No early-access builds, nothing behind a Patreon, automatic purges included.
- **New Minecraft versions the same week.** xRewind uses only Paper's API, never the server's internals, so an update rarely breaks it.
- **You won't feel it running.** Nothing touches the database on a game thread. Each change is queued as a few numbers and written in batches in the background, and logging you turn off isn't listened for at all.
- **Rollback that doesn't freeze the server.** Each block's final state is worked out once, then applied a few milliseconds per tick (region by region on Folia). Preview a rollback before you apply it, and undo it after.
- **Easy to switch to.** CoreProtect's command syntax, so habits and guides carry over, and your CoreProtect history comes with you.
- **Every change applies on reload**, even what's logged and where it's stored. No restarts.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia.

---

## What it logs

**On out of the box:** blocks placed and broken, with what they held and what falls off with them · explosions, with TNT traced to whoever lit it · fire · buckets and liquids that destroy something · pistons · mobs that change blocks · sign text · items in and out of containers · kills · joins and quits.

**Off until you turn them on:** hoppers, door and lever clicks, leaf decay, chat and commands.

A rollback brings back what a broken block held: a chest's items, a sign's text.

---

## What your staff get

- `/xr inspect`: click a block to see its history.
- `/xr lookup` with CoreProtect's parameters: `u:` `t:` `r:` `a:` `i:` `e:` `w:`.
- `/xr rollback` and `/xr restore`, with `#preview` to see the result first, and `/xr undo` if you change your mind.
- `/xr purge` to clear old history by hand, on top of the automatic purge (30 days by default).
- `/xr status` to check that logging is keeping up.

Each command has its own permission, all op-only until you give them out, so helpers can look things up without being able to roll anything back.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. It logs to SQLite in its own folder, with nothing to set up.
2. Turn on anything extra you want logged in `plugins/xRewind/config.yml`, or switch to MySQL/MariaDB for a network.
3. Give your staff the permissions they need: `xrewind.inspect`, `xrewind.lookup`, `xrewind.rollback`.

**Coming from CoreProtect?** Install xRewind next to it, run `/xr import coreprotect`, and your history comes across: blocks, kills, containers, sessions, signs, chat and commands. Then remove CoreProtect.

Full setup, every setting and every permission: **[docs.xenastudios.co/xrewind](https://docs.xenastudios.co/xrewind/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xrewind)
