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

If xVote had already made its own, run `/xvote import nuvotifier`: what NuVotifier has (its keys, its tokens, or both) takes the place of xVote's, which are kept as `.before-import` files. What NuVotifier doesn't have is left as it is.

Plugins that listened for NuVotifier's vote event need NuVotifier, which can't run beside xVote on one machine's port. If your proxy runs NuVotifier and forwards to the servers, see [Networks](/xvote/networks/#nuvotifier-on-the-proxy). Use xVote's rewards in their place, or ask the plugin's author to use [xVote's API](/xvote/developer-api/).

## From VotingPlugin

1. Note your sites, rewards and vote party from VotingPlugin's config, and write them in xVote's [config](/xvote/configuration/).
2. Keep VotingPlugin's folder, remove its jar (and NuVotifier's, as above), and start the server with xVote.
3. Run `/xvote import votingplugin`.

It reads a copy of VotingPlugin's SQLite database (`plugins/VotingPlugin/Users.db`, so VotingPlugin's own file is never opened) and adds each player's all-time total to xVote's, with when they last voted. Day, week and month totals start from the switch, since VotingPlugin doesn't keep single votes. A VotingPlugin on MySQL can't be imported yet: switch its storage to SQLite first.

## From SuperbVote

Run `/xvote import superbvote`. It reads `plugins/SuperbVote/votes.json`, or SuperbVote's MySQL table when its config uses MySQL (the `storage.mysql` settings in SuperbVote's config, with its defaults: port 3306, table `superbvote`), and adds each player's all-time total.

## Importing twice

Each import is recorded. Running it again says when it was done; `/xvote import votingplugin again` adds the totals a second time, which you only want if the first import was undone.
