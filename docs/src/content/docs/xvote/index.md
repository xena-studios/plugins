---
title: xVote
description: "xVote: vote rewards with a built-in Votifier for Paper and Folia. Keeps NuVotifier's keys and tokens, rewards per site, streaks, a vote party and leaderboards, and pays each vote once, even across a network."
sidebar:
  label: Overview
  order: 1
---

**Votes in. Rewards out.**

xVote receives votes from server lists and rewards players for them, in one jar. Most servers need two plugins for this, NuVotifier to receive and another to reward; NuVotifier hasn't had a release since 2021. xVote does both, and takes over NuVotifier's keys and tokens so your lists keep working.

- **A Votifier built in.** It speaks Votifier v1 and v2 exactly as NuVotifier does, on its own threads, with limits that keep floods and slow connections out. See [Setting up](/xvote/setting-up/).
- **Every vote paid once.** Each vote is a row in a ledger, and a vote is taken by one server before it's paid. Offline players are paid when they join; a name that has never joined waits until it does. On a network, the player is paid on whichever server they're on.
- **Rewards your way.** Money (through Vault), items, commands, messages, broadcasts and sounds, with a chance each. Give them on every vote, per site, on the first vote of the day, every Nth vote, at streak milestones and at vote totals.
- **For players.** `/vote` lists each site with a link and when it opens again. `/vote top` has today's, this week's, this month's and all-time leaderboards. Reminders when they join, and a vote party for everyone.
- **You won't feel it running.** Totals and leaderboards are kept in memory; the database is written on its own thread. Nothing repeats unless you turn on timed reminders.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

Current version: **1.0.0**, for Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. [Download on Modrinth](https://modrinth.com/plugin/xvote).

## How it works

A server list sends a vote to xVote's **receiver**, on its own port. The vote names a player and the list it came from (its **service**). xVote finds the player, stores the vote, and gives the rewards it earned. If the player is offline, the vote waits and is paid when they join.

A vote from the same site sooner than that site's **cooldown** is a repeat and is dropped. So is a replayed vote with the same timestamp.

**Totals** are counted per day, week and month, in the time zone you set, plus all time. A **streak** is the days in a row a player has voted.

## Storage

Votes are kept in SQLite out of the box, in `plugins/xVote/votes.db`, or in MySQL/MariaDB. Several servers can share one MySQL database: see [Networks](/xvote/setting-up/#networks).

## Start here

1. [Install it](/start/installing/). Remove NuVotifier: xVote takes over its keys and tokens on the first start.
2. Open the receiver's port and tell your lists: [Setting up](/xvote/setting-up/).
3. Set your sites and rewards: [Configuration](/xvote/configuration/).
4. Coming from another voting plugin: [Switching](/xvote/switching/).
5. Every command and permission: [Commands](/xvote/commands/).
