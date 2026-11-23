---
title: Developer API
description: Read xVote's totals and leaderboards, add reward types, and send votes to it from your own plugin.
sidebar:
  order: 6
---

xVote's API is in the `xvote-api` artifact, which xVote ships inside its own jar. Compile against it, and add xVote to your plugin's `depend` or `softdepend`.

```kotlin
dependencies {
    compileOnly("co.xenastudios.xvote:xvote-api:1.0.0")
}
```

It's on Maven Central, so `mavenCentral()` is all the repository you need, and its Javadoc is on [javadoc.io](https://javadoc.io/doc/co.xenastudios.xvote/xvote-api).

## Reading

```java
XVoteApi votes = XVoteApi.get();
VoterStats stats = votes.stats(player.getUniqueId());
int month = stats.month();
List<TopVoter> top = votes.top(Period.WEEK, 10);
PartyProgress party = votes.party();
```

Every read answers from memory and returns at once, on any thread.

## Reward types

Add a reward that server owners can use in xVote's config:

```java
XVoteApi.get().registerRewardType(this, "cratekey", (player, argument) -> giveKey(player, argument));
```

A reward with `custom: ["cratekey vote 1"]` then calls it with `vote 1`, on the player's own thread, while they're online.

## Votes from elsewhere

A website's webhook or a Discord bot can send votes through the same ledger, checks and rewards:

```java
XVoteApi.get().submitVote("MyWebsite", "Steve", "").thenAccept(result -> ...);
```

The future completes off the server's threads with what happened: `COUNTED`, `WAITING` (no player with that name has joined yet), `DUPLICATE`, `INVALID`, `CANCELLED` or `FAILED`.

## Events

| Event | Fired | Cancelling it |
| --- | --- | --- |
| `VoteReceivedEvent` | Off the server's threads, before a vote is stored | Drops the vote. |
| `VoteRewardEvent` | On the player's thread, before rewards are given | Gives nothing for that vote; it still counts. `getRewards()` can leave some out. |
| `VotePartyEvent` | On the global thread of each server, before the party's rewards | Gives this server's players nothing. |

Each is only made when something listens for it.
