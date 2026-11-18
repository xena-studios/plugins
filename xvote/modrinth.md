![xVote by Xena Studios. A periwinkle pixel icon of a ticked ballot over its box and the xVote name over a stepped horizon in periwinkle, with the headline "Votes in. Rewards out."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xvote/banner.png)

## Votes in. Rewards out.

Vote rewards and a Votifier in one jar. Most servers run two plugins for this, NuVotifier to receive votes and another to reward them, and NuVotifier hasn't had a release since 2021. **xVote does both, and takes over NuVotifier's keys and tokens on its first start, so every server list keeps working without you touching it.**

**[Read the docs](https://docs.xenastudios.co/xvote/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **A Votifier built in.** Votifier v1 and v2, byte for byte as NuVotifier speaks them, so every list that works with NuVotifier works with xVote. It runs on its own threads, with limits per address, a deadline per connection and a cap on how many at once, so a flood or a slow connection can't touch your server.
- **Every vote paid once.** Every vote is a row in a ledger, and a server takes a vote in the database before paying it. Offline players are paid when they join. A name that has never joined waits until it does, Bedrock prefix or not.
- **Networks without a proxy plugin.** Share one MySQL database and a vote for a player on another backend is paid there, within seconds. Never twice, even when two servers try at once.
- **You won't feel it running.** Totals and leaderboards live in memory, the database is written on its own thread, and nothing repeats unless you turn on timed reminders. Works on Folia.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

---

## Rewards your way

- **Money** through Vault, **items** written like `/give` (held for `/vote claim` when the inventory is full, never dropped), **console commands**, messages, broadcasts and sounds, each with a chance.
- On **every vote**, for **one site**, the **first vote of the day**, **every Nth vote**, **streak milestones** and **vote totals**.
- A **vote party**: every so many votes, a reward for everyone online, or everyone who voted.
- Other plugins can add their own reward types, such as crate keys.

---

## What players get

- **`/vote`:** every site with a link to click, and when each opens again: *"✓ Planet Minecraft, again in 3h"*.
- **`/vote top`:** today's, this week's, this month's and all-time leaderboards, a page at a time.
- **Reminders** when they join, if there's a site they can vote on.
- **PlaceholderAPI:** totals, streaks, sites open, the party's progress and the top voters.

---

## Up and running in a minute

1. Drop the jar in `plugins/` (remove NuVotifier) and start the server. Votes are kept in SQLite in its own folder, with nothing to set up.
2. Open port 8192, or the port your host gives you, and give your lists the key from `/xvote key` or the token from `/xvote token`.
3. Put your sites and rewards in `plugins/xVote/config.yml`, then `/xvote reload`. `/vote test <player>` sends a vote end to end.

Coming from VotingPlugin or SuperbVote? `/xvote import votingplugin` or `/xvote import superbvote` brings every player's total across.

Full setup, every setting and every permission: **[docs.xenastudios.co/xvote](https://docs.xenastudios.co/xvote/)**

**Requires** Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer, and a second port for the receiver. Vault and an economy plugin for money rewards; PlaceholderAPI is optional. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute. Select an area and every plot and sign is made for you.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xvote)
