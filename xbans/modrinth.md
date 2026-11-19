![xBans by Xena Studios. A crimson pixel gavel icon and the xBans name over a stepped horizon in crimson, with the headline "Bans, mutes and warnings. Nothing slips through."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xbans/banner.png)

## Bans, mutes and warnings. Nothing slips through.

A banned player gets back in while the database is slow. A muted one carries on through `/emsg`, a sign or a book. Staff stop trusting the ban plugin. **xBans checks every ban and mute in force from memory, at every join and every chat message, so nothing gets round it.** Bans, temporary and IP bans, mutes, warnings and kicks, with history, alts, templates and staff limits, and every feature free.

**[Read the docs](https://plugins.xenastudios.co/xbans/)** · **[Switching from LiteBans](https://plugins.xenastudios.co/xbans/switching/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **Bans that hold.** Every ban and mute in force is kept in memory and loaded before anyone can join, so the join check never waits on the database and keeps working if MySQL drops for a moment. A ban made while someone is connecting still catches them.
- **Mutes with no way round.** Chat, `/msg`, `/r`, `/me` and any command you list, by every alias and namespace (`/essentials:msg`, `/emsg`), plus signs, books and anvil renames, and chat plugins on the older chat events too.
- **Nothing is ever deleted.** An unban is recorded beside the ban, with who, when and why. Only `/prunehistory` deletes, when you tell it to.
- **You won't feel it running.** The join and chat checks are lookups in memory. Writes go through one background thread. History pages are read by key range, so the 300th page is as quick as the first. Nothing repeats on a single server.
- **Every change applies on reload**, even switching to another database. No restarts.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia.
- **Easy to switch to.** LiteBans' command syntax (`/ban Steve -s 7d server:survival Hacking`, `/tempmute Alex 1d2h`, `/unban #123`, `-s`, `-S`, `-p`), so your staff's habits carry over, and your LiteBans history comes with you.

---

## What it does

- **Bans, mutes, warnings and kicks**, permanent or timed, by account or by IP. A player who has never joined can be banned before they arrive.
- **Templates and escalation:** `/ban Steve hacking` gives 7 days, then 30, then permanent. Three warnings can mute for an hour.
- **Alt alerts** when a player joins who shares an IP with a banned account.
- **A ban screen** with the reason, time left and your appeal link.
- **Replaces vanilla** `/ban`, `/pardon`, `/ban-ip`, `/pardon-ip`, `/kick` and `/banlist`.
- **Networks without a proxy plugin.** Point every backend server at the same MySQL database and turn on network mode: a ban on one server kicks the player from the others, and every server's ban list has it within a few seconds. Scope a ban to one server with `server:`.
- **IP privacy.** IP addresses are personal data. With `ips.hash: true` xBans stores a salted hash of each one instead: IP bans and alts still work, but there's no list of addresses to leak. Or turn IP recording off altogether.

---

## What your staff get

- **Punishing:** `/ban`, `/tempban`, `/ipban`, `/mute`, `/tempmute`, `/ipmute`, `/warn`, `/kick`, and `/unban`, `/unmute`, `/unwarn` by name, IP or `#id`.
- **Lookups:** `/history`, `/staffhistory`, `/checkban`, `/checkmute`, `/warnings`, `/banlist`, `/mutelist`, `/alts` (`/dupeip`) and `/iphistory`, clickable and paged.
- **Staff limits:** weights so helpers can't punish moderators, exempt players, and the longest ban and mute per group.

Each command has its own permission, all op-only until you give them out (except `/warnings`, where everyone may see their own).

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. Punishments are kept in SQLite in its own folder, with nothing to set up.
2. Set up templates, staff limits and what mutes block in `plugins/xBans/config.yml`, and apply them with `/xbans reload`.
3. Give your staff the permissions they need: `xbans.ban`, `xbans.mute`, `xbans.history` and the rest.

**Coming from LiteBans?** Run `/xbans import litebans` to see what it finds, then add `confirm`: bans, IP bans, mutes, warnings and kicks come across, with who made and revoked each, and the login history for alts. It reads LiteBans' MySQL or MariaDB database; on H2, LiteBans' default, move to MySQL first, as the switching guide shows. `vanilla` brings the server's ban lists and `essentials` EssentialsX's mutes. Importing twice adds nothing.

Full setup, every setting and every permission: **[plugins.xenastudios.co/xbans](https://plugins.xenastudios.co/xbans/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

<!-- catalog:start: the other released plugins, from the brand catalog (pnpm catalog in docs/). -->
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. See what a banned griefer broke or took, and roll it back.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server for when your main one goes down. With xBans installed there too, it enforces the same bans.

Every plugin in the family: **[plugins.xenastudios.co](https://plugins.xenastudios.co/)**
<!-- catalog:end -->

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xbans)
