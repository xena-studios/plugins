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
| `punishments` | Each ban, mute, warning and kick: who, by whom, why, when, until when, which server. For IP punishments, the IP. | The history, and what's in force. |
| `removals` | Who revoked a punishment, when and why. | So an unban never erases the ban. |
| `players` | Each player's uuid, last name, first and last join, and their weight and exemption when they last joined. | Names for offline players, and staff limits while they're offline. |
| `ips` | Each IP a player joined from, with the first and last time. | IP bans and mutes, `/alts`, `/iphistory` and the alt alert. |

## IP addresses are personal data

In much of the world, including under the EU's GDPR, an IP address is personal data. Keeping one needs a reason, such as keeping banned players from coming back on another account, and should be kept no longer than that reason needs. Say in your server's rules or privacy notice that you keep them, and why.

xBans gives you two ways to keep less:

- **Hash them.** With `ips.hash: true`, xBans stores a salted hash of each address instead of the address. IP bans, IP mutes, `/alts` and the alt alert work just as before, because the same address always gives the same hash, but there's no list of addresses in the database to leak or hand over. `/iphistory` shows the start of each hash. The salt is in `plugins/xBans/ip-salt.txt`, never in the database: a stolen database alone can't be turned back into addresses. Keep that file private, and the same on every server of a network.

  Turning hashing on hashes every address already stored. It can't be turned back off for that database: there are no addresses left to go back to.

- **Don't record them.** With `ips.enabled: false`, xBans stores no IPs at all. IP bans, IP mutes, `/alts` and `/iphistory` are off.

xBans never records loopback addresses (`127.0.0.1`), which are what every player seems to come from behind a proxy that doesn't forward their address. Set up your proxy's forwarding so xBans sees players' real addresses.

## Deleting a player's history

`/prunehistory <player> confirm` deletes every punishment of theirs that's no longer in force: ended, revoked, and kicks. With a duration (`/prunehistory Steve 30d confirm`), only those made in that time. Their IP records and name stay; for a full erasure request, delete their rows from the `ips` and `players` tables too, by uuid.

See also [Privacy](/start/privacy/) for what every Xena plugin sends.
