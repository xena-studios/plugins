---
title: Developer API
description: Use xStats from your own plugin. Read stats, ranks and leaderboards, add stats of your own, add sections to player profiles, and listen for saves.
sidebar:
  order: 10
---

This page is for plugin developers. xStats has an API for other plugins: read any player's stats, ranks and leaderboards, count stats of your own, add a section to player profiles on `/stats` and the website, and hear when stats are saved or a leaderboard has a new leader.

The API costs nothing unless a plugin uses it. It adds no listeners and no tasks, and its events are only made when something listens for them.

## Add it to your build

The API is `co.xenastudios.xstats:xstats-api`. It's inside the xStats jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

It's on Maven Central, so `mavenCentral()` is all the repository you need, and its Javadoc is on [javadoc.io](https://javadoc.io/doc/co.xenastudios.xstats/xstats-api).

```kotlin
dependencies {
    compileOnly("co.xenastudios.xstats:xstats-api:1.0.1")
}
```

```xml
<dependency>
    <groupId>co.xenastudios.xstats</groupId>
    <artifactId>xstats-api</artifactId>
    <version>1.0.1</version>
    <scope>provided</scope>
</dependency>
```

Then add xStats to `depend` (or `softdepend`) in `plugin.yml`. A Paper plugin lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
XStatsApi stats = XStatsApi.get();
```

`get()` throws an `IllegalStateException` if xStats isn't enabled; `Bukkit.getServicesManager().load(XStatsApi.class)` returns null instead. With `softdepend`, check that xStats is enabled before touching its classes.

Unless a method says otherwise, it may be called from any thread, answers from memory and returns at once. Values are as of xStats' last save, every few minutes, which is also when leaderboards are worked out.

## Read stats and leaderboards

```java
long kills = stats.getStat(player.getUniqueId(), "kills", Period.MONTHLY);
int place = stats.getRank(player.getUniqueId(), "kills", Period.ALL);   // 0: not on it
List<LeaderboardEntry> top = stats.getTop("playtime", Period.WEEKLY, 10);
List<StatInfo> all = stats.getStats();
```

Values are whole numbers in the stat's `StatUnit`: seconds for `TIME`, centimetres for `DISTANCE`, tenths of a health point for `DAMAGE`, and `COUNT` with `StatInfo#decimals()` places. `getProfile(uuid)` returns everything about a player as a `CompletableFuture<Optional<StatsProfile>>`; `isPrivate(uuid)` says whether they ran `/stats private`.

## Count your own stats

```java
CustomStat votes = stats.registerStat(plugin, "votes", "Votes", StatUnit.COUNT);       // xvote_votes
CustomStat balance = stats.registerStat(plugin, "balance", "Balance", StatUnit.COUNT, true); // true: a value that's set

votes.increment(player.getUniqueId(), 1);   // a vote: counts today, this week, this month and all time
balance.set(player.getUniqueId(), cents);   // a value that goes up and down: only rises count towards periods
```

A registered stat is kept, saved, ranked and shown like xStats' own: on `/stats`, `/leaderboard`, placeholders (`%xstats_xvote_votes_monthly%`) and the website, with daily, weekly and monthly numbers. Its full id is your plugin's name in lowercase, an underscore and your id. Changes are queued and saved with xStats' next save. Pass `true` as the last argument for a value you `set`, such as a balance: on a [network](/xstats/v1/networks/) it's the latest value any server set. Without it, a stat is a count, and a network adds up every server's. Register in `onEnable`; registering again returns the same stat, and stats are forgotten when your plugin is disabled (their saved values stay, and come back when you register again).

## Add a section to profiles

```java
stats.registerProfileSection(plugin, "votes", "Voting", (uuid, staff) -> List.of(
    new ProfileLine("Votes this month", Integer.toString(votesThisMonth(uuid))),
    new ProfileLine("Streak", streak(uuid) + " days")));
```

The section shows on `/stats`, the website and the web API's player page. It's asked off the server's threads whenever a profile is shown, so answer from memory: a provider that takes over half a second is skipped for that profile, and one that throws is logged once and skipped. `staff` is true when staff with `xstats.staff` look in game, so a section can show them more (a full history, say) and show the public a summary. Lines are plain text everywhere: the website never reads them as HTML. Return an empty list to leave the section out.

## Events

| Event | When |
| --- | --- |
| `StatsSavedEvent` | After each save: stats are in the database and leaderboards are worked out. `getPlayers()`, `getMillis()`. |
| `LeaderboardChangeEvent` | A leaderboard has a new leader: `getStat()`, `getPeriod()`, `getPrevious()`, `getLeader()`, `getLeaderName()`, `getValue()`. Not fired when a new day empties a board, nor for the boards worked out at startup. |

Both are fired asynchronously, on xStats' own thread. To touch the world or a player from one, go to the right thread first (the player's scheduler, or the global one).

## Works with the family

Each of these will plug into xStats through the section and stat API above, on when both plugins are installed and with a switch to turn it off:

| Plugin | On profiles and the site |
| --- | --- |
| xBans | Punishment history (a count by default, the full list for staff), banned players left out of leaderboards, and a public ban list page |
| xVote | Votes per period, streak, top voters |
| xMoney | Balance and a baltop board |
| xPlots | Plots rented |
| xTeleport | Homes set, random teleports |
| zChat | Messages sent, mentions |

They come in later releases.
