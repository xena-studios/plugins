![xRewind by Xena Studios. A teal pixel rewind icon and the xRewind name over a stepped horizon in teal, with the headline "Every change, logged. Any of it, undone."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xrewind/banner.png)

## Every change, logged. Any of it, undone.

Someone griefed spawn. Someone emptied the shared chest. Someone says they didn't. **xRewind shows you who placed, broke or took what, where and when, and puts it back.**

> **Being built.** xRewind isn't released yet. Follow it on [GitHub](https://github.com/xena-studios/plugins/issues/20) and the first release will land here.

**[Read the docs](https://docs.xenastudios.co/xrewind/)** · **[The plan](https://github.com/xena-studios/plugins/issues/20)** · **[Source code](https://github.com/xena-studios/plugins)**

---

## Why it's different

- **Every feature free.** No early-access builds, nothing behind a Patreon, automatic purges included.
- **New Minecraft versions the same week.** xRewind uses only Paper's API, never the server's internals, so an update rarely breaks it.
- **You won't feel it running.** Nothing touches the database on a game thread. Each change is queued as a few numbers and written in batches in the background, and logging you turn off isn't listened for at all.
- **Rollback that doesn't freeze the server.** Each block's final state is worked out once, then applied a few milliseconds per tick (region by region on Folia). Preview a rollback before you apply it, and undo it after.
- **Easy to switch to.** CoreProtect's command syntax, so habits and guides carry over, and your CoreProtect history comes with you.

---

## What it logs

Blocks placed and broken, and what falls off with them · explosions, traced to whoever lit them · fire · liquids that destroy something · pistons · mobs that change blocks · sign text · items in and out of containers · kills · joins and quits. Chat and commands too, if you turn them on.

## Commands

`/xr inspect` · `/xr lookup` · `/xr rollback` · `/xr restore` · `/xr undo` · `/xr purge`, with CoreProtect's parameters: `u:` `t:` `r:` `a:` `i:` `e:` and `#preview`.

---

SQLite out of the box, or MySQL/MariaDB for networks. For Paper 1.21.11 and newer, Purpur and Folia, on Java 21 or newer. Free and open source under GPL-3.0.
