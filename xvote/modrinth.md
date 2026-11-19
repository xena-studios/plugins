![xVote by Xena Studios. A cobalt pixel icon of a ticked ballot over its box and the xVote name over a stepped horizon in cobalt, with the headline "Votes in. Rewards out."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xvote/banner.png)

## Votes in. Rewards out.

Vote rewards usually take two plugins: one to receive votes from server lists, and another to reward them. Then a network pays a vote twice, or an offline player's vote is lost. **xVote receives and rewards votes in one jar, and pays every vote exactly once, even across a network.**

**[Read the docs](https://plugins.xenastudios.co/xvote/)** · **[Switching from NuVotifier, VotingPlugin or SuperbVote](https://plugins.xenastudios.co/xvote/switching/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **A Votifier built in.** Votifier v1 and v2, so every server list that sends Votifier votes works with xVote. It runs on its own threads, with limits per address, a deadline per connection and a cap on how many at once, so a flood or a slow connection can't touch your server.
- **Every vote paid once.** Every vote is a row in a ledger, and a server takes a vote in the database before paying it. Offline players are paid when they join. A name that has never joined waits until it does, Bedrock prefix or not.
- **Networks without a proxy plugin.** Share one MySQL database and a vote for a player on another backend is paid there, within seconds. Never twice, even when two servers try at once.
- **You won't feel it running.** Totals and leaderboards live in memory, the database is written on its own thread, and nothing repeats unless you turn on timed reminders.
- **Every change applies on reload**, sites and rewards included. Only storage and network settings wait for a restart.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia.
- **Easy to switch to.** It takes over NuVotifier's keys and tokens on its first start, so every list keeps working without you touching it, and imports every player's total from VotingPlugin or SuperbVote.

---

## Rewards your way

- **Money** through Vault, **items** written like `/give` (held for `/vote claim` when the inventory is full, never dropped), **console commands**, messages, broadcasts and sounds, each with a chance.
- On **every vote**, for **one site**, the **first vote of the day**, **every Nth vote**, **streak milestones** and **vote totals**.
- A **vote party**: every so many votes, a reward for everyone online, or everyone online who voted since the last one.
- Other plugins can add their own reward types, such as crate keys.

---

## What players get

- **`/vote`:** every site with a link to click, and when each opens again: *"✓ Planet Minecraft, again in 3h"*.
- **`/vote top`:** today's, this week's, this month's and all-time leaderboards, a page at a time.
- **Reminders** when they join, if there's a site they can vote on.
- **PlaceholderAPI:** totals, streaks, sites open, the party's progress and the top voters.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. Votes are kept in SQLite in its own folder, with nothing to set up.
2. Open port 8192, or the port your host gives you, and give your lists the key from `/xvote key` or the token from `/xvote token`.
3. Put your sites and rewards in `plugins/xVote/config.yml`, then `/xvote reload`. `/vote test <player>` sends a vote end to end.

**Coming from NuVotifier?** Remove its jar, keep its `plugins/Votifier/` folder, and start the server with xVote: its keys, tokens and port come across, so no list needs changing. **From VotingPlugin or SuperbVote?** `/xvote import votingplugin` or `/xvote import superbvote` brings every player's total across.

Full setup, every setting and every permission: **[plugins.xenastudios.co/xvote](https://plugins.xenastudios.co/xvote/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer, and a second port for the receiver. Vault and an economy plugin for money rewards; PlaceholderAPI is optional. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

<!-- catalog:start: the other released plugins, from the brand catalog (pnpm catalog in docs/). -->
- **[xMoney](https://plugins.xenastudios.co/xmoney/)** (coming soon): The economy and Vault, in one plugin. Money rewards through Vault, with no Vault.jar installed.
- **[xStats](https://plugins.xenastudios.co/xstats/)** (coming soon): Every player's stats, on your own website. Turn xVote's placeholders into daily, weekly and monthly leaderboards, and onto your stats website.

Every plugin in the family: **[plugins.xenastudios.co](https://plugins.xenastudios.co/)**
<!-- catalog:end -->

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xvote)
