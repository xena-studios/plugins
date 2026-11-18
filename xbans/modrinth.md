![xBans by Xena Studios. A crimson pixel gavel icon and the xBans name over a stepped horizon in crimson, with the headline "Bans, mutes and warnings. Nothing slips through."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xbans/banner.png)

## Bans, mutes and warnings. Nothing slips through.

A punishment plugin for Paper and Folia, free and open source: bans, temporary bans, IP bans, mutes, warnings and kicks, with history, alts, templates and staff limits. **Every ban and mute in force is checked from memory, so a banned player never gets in while the database thinks, and a muted one finds no alias, sign or book to talk through.**

**[Read the docs](https://docs.xenastudios.co/xbans/)** · **[Switching from LiteBans](https://docs.xenastudios.co/xbans/switching/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Bans that hold.** Every ban and mute in force is kept in memory and loaded before anyone can join, so the join check never waits on the database and keeps working if MySQL drops for a moment. A ban made while someone is connecting still catches them.
- **Mutes with no way round.** Chat, `/msg`, `/r`, `/me` and any command you list, by every alias and namespace (`/essentials:msg`, `/emsg`), plus signs, books and anvil renames, and chat plugins on the older chat events too.
- **Nothing is ever deleted.** An unban is recorded beside the ban, with who, when and why. Only `/prunehistory` deletes, when you tell it to.
- **You won't feel it running.** The join and chat checks are lookups in memory. Writes go through one background thread. History pages are read by key range, so the 300th page is as quick as the first. Nothing repeats on a single server.
- **Every feature free.** No premium version, no early-access builds.
- **Easy to switch to.** LiteBans' command syntax (`/ban Steve -s 7d server:survival Hacking`, `/tempmute Alex 1d2h`, `/unban #123`, `-s`, `-S`, `-p`), so your staff's habits carry over, and your LiteBans history comes with you.

---

## What your staff get

- **Bans, mutes, warnings and kicks**, permanent or timed, by account or by IP: `/ban`, `/tempban`, `/ipban`, `/mute`, `/tempmute`, `/ipmute`, `/warn`, `/kick`, and `/unban`, `/unmute`, `/unwarn` by name, IP or `#id`.
- **Lookups:** `/history`, `/staffhistory`, `/checkban`, `/checkmute`, `/warnings`, `/banlist`, `/mutelist`, `/alts` (`/dupeip`) and `/iphistory`, clickable and paged.
- **Templates and escalation:** `/ban Steve hacking` gives 7 days, then 30, then permanent. Three warnings can mute for an hour.
- **Staff limits:** weights so helpers can't punish moderators, exempt players, and the longest ban and mute per group.
- **Alt alerts** when a player joins who shares an IP with a banned account.
- **Replaces vanilla** `/ban`, `/pardon`, `/ban-ip`, `/pardon-ip`, `/kick` and `/banlist`.
- **A ban screen** with the reason, time left and your appeal link.

---

## Switching is one command

`/xbans import litebans` reads LiteBans' MySQL or MariaDB database (with LiteBans' own settings), counts what's there, and imports it all on `confirm`: bans, IP bans, mutes, warnings and kicks, with who made and revoked each, and the login history for alts. `vanilla` brings the server's ban lists and `essentials` EssentialsX's mutes. Importing twice adds nothing.

---

## Privacy

IP addresses are personal data. With `ips.hash: true` xBans stores a salted hash of each one instead: IP bans and alts still work, but there's no list of addresses to leak. Or turn IP recording off altogether.

---

## Networks

Point every backend server at the same MySQL database and turn on network mode: a ban on one server kicks the player from the others, and every server's ban list has it within a few seconds. Bans can be scoped to one server with `server:`.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. Punishments are kept in SQLite in its own folder, with nothing to set up.
2. Give your staff `xbans.ban`, `xbans.mute`, `xbans.history` and the rest.
3. Coming from LiteBans? `/xbans import litebans`.

Templates, staff limits, what mutes block, and every message and screen are in `plugins/xBans/config.yml`, and apply on `/xbans reload`.

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xbans)
