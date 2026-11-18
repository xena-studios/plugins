---
title: Switching
description: Move to xVote from NuVotifier, VotingPlugin or SuperbVote without touching your server lists.
sidebar:
  order: 3
---

## From NuVotifier

xVote takes over NuVotifier's setup on its first start, so you don't touch a single list.

1. Stop the server.
2. Remove NuVotifier's jar, and keep its folder, `plugins/Votifier/`.
3. Add xVote and start the server.

The console says `Took over NuVotifier's keys and tokens`. Your RSA keys (`rsa/`), your tokens and NuVotifier's port and v1 setting are copied into xVote. Every list keeps the public key and token it has.

If xVote had already made its own, run `/xvote import nuvotifier`: xVote's are kept as `.before-import` files, and NuVotifier's take their place.

Plugins that listened for NuVotifier's vote event need NuVotifier, which can't run beside xVote: both want the same port. Use xVote's rewards in their place, or ask the plugin's author to use [xVote's API](/xvote/developer-api/).

## From VotingPlugin

1. Note your sites, rewards and vote party from VotingPlugin's config, and write them in xVote's [config](/xvote/configuration/).
2. Keep VotingPlugin's folder, remove its jar (and NuVotifier's, as above), and start the server with xVote.
3. Run `/xvote import votingplugin`.

It reads VotingPlugin's SQLite database (`plugins/VotingPlugin/Users.db`) and adds each player's all-time total to xVote's. Day, week and month totals start from the switch, since VotingPlugin doesn't keep single votes. A VotingPlugin on MySQL can't be imported yet: switch its storage to SQLite first.

## From SuperbVote

Run `/xvote import superbvote`. It reads `plugins/SuperbVote/votes.json`, or SuperbVote's MySQL table when its config uses MySQL, and adds each player's all-time total.

## Importing twice

Each import is recorded. Running it again says when it was done; `/xvote import votingplugin again` adds the totals a second time, which you only want if the first import was undone.
