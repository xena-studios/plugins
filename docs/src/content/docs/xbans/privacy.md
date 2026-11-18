---
title: Privacy
description: What xBans stores about players, how to hash IP addresses, and how to delete history.
sidebar:
  order: 6
---

xBans keeps punishments and what it needs to enforce them in your own database. Nothing of it leaves your server; network mode only shares it with the other servers that use the same database.

## What's stored

| Table | What | Why |
| --- | --- | --- |
| `punishments` | Each ban, mute, warning and kick: who, by whom (and that staff member's weight), why, when, until when, which server. For IP punishments, the IP. | The history, and what's in force. |
| `removals` | Who revoked a punishment, when and why. | So an unban never erases the ban. |
| `players` | Each player's uuid, last name, first and last join, and their weight and exemption when they last joined. | Names for offline players, and staff limits while they're offline. |
| `ips` | Each IP a player joined from, with the first and last time. | IP bans and mutes, `/alts`, `/iphistory` and the alt alert. |

## IP addresses are personal data

In much of the world, including under the EU's GDPR, an IP address is personal data. Keeping one needs a reason, such as keeping banned players from coming back on another account, and should be kept no longer than that reason needs. Say in your server's rules or privacy notice that you keep them, and why.

xBans gives you two ways to keep less:

- **Hash them.** With `ips.hash: true`, xBans stores a salted hash of each address instead of the address. IP bans, IP mutes, `/alts` and the alt alert work just as before, because the same address always gives the same hash, but there's no list of addresses in the database to leak or hand over. `/iphistory` shows the start of each hash. The salt is in `plugins/xBans/ip-salt.txt`, never in the database: a stolen database alone can't be turned back into addresses. Keep that file private, and the same on every server of a network.

  With SQLite, the salt and the database are in the same folder, so a copy of that folder has both: keep backups of it as private as the database. With MySQL, the salt stays on the game servers and the database server never sees it.

  Turning hashing on hashes every address already stored, the names of IP bans made without an account, and the record of where each imported IP ban came from, all in one go: if the server stops halfway, nothing has changed and it starts again from the beginning next time. It can't be turned back off for that database: there are no addresses left to go back to. On a network, see [turning hashing on](/xbans/networks/#turning-on-ip-hashing).

  A hashed database only opens with the salt it was hashed with. If `ip-salt.txt` is missing or isn't the right one, xBans doesn't make a new one (no IP ban would match anyone with it): it says so in the console and lets nobody join until the right file is back, trying again every 15 seconds.

  **If the salt is lost for good** (no backup, no other server has it), create an empty file named `reset-ip-salt.txt` in `plugins/xBans/` and start the server. xBans starts over with the `ip-salt.txt` that's there, or makes a new one, and deletes `reset-ip-salt.txt`, so it happens once. IP bans, IP mutes and the record of which accounts shared an IP, made before, no longer match anyone, because their hashes can't be worked out again; account bans and mutes aren't affected. New IP records build up again as players join. On a network, stop every server first, reset on one, then copy its new `ip-salt.txt` to the others before starting them.

- **Don't record them.** With `ips.enabled: false`, xBans stores no IPs at all. IP bans, IP mutes, `/alts` and `/iphistory` are off.

## Where addresses are shown

Full addresses are shown only to staff with `/iphistory` and `/alts`. An IP ban of an address with no account is named after its first half (`203.0.x.x`), or the start of its hash, in the broadcast everyone sees, ban lists and history. The developer API never shows addresses.

xBans never records loopback addresses (`127.0.0.1`), which are what every player seems to come from behind a proxy that doesn't forward their address. Set up your proxy's forwarding so xBans sees players' real addresses. If many players online share one address, xBans warns in the console that the proxy may not be forwarding them.

## Deleting a player's history

`/prunehistory <player> confirm` deletes every punishment of theirs that's no longer in force: ended, revoked, and kicks. With a duration (`/prunehistory Steve 30d confirm`), only those made in that time. Each prune is written to the console: who, whose and how many. Their IP records and name stay; for a full erasure request, delete their rows from the `ips` and `players` tables too, by uuid.

See also [Privacy](/start/privacy/) for what every Xena plugin sends.
