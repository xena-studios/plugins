![xVote by Xena Studios. A cobalt pixel icon of a ticked ballot over its box and the xVote name over a stepped horizon in cobalt, with the headline "Votes in. Rewards out."](../.github/assets/xvote-banner.png)

**Votes in. Rewards out.**

xVote receives votes from server lists and rewards players for them, in one jar: a Votifier that speaks NuVotifier's protocols and keeps its keys and tokens, and the rewards, leaderboards and vote party that usually need a second plugin.

- **A Votifier built in.** v1 and v2, byte for byte as NuVotifier speaks them, on its own threads, with limits per address, per connection and in total.
- **Every vote paid once.** Every vote is a row; a server takes a vote in the database before paying it, so two servers sharing a database can't both pay it. Offline votes are paid on join.
- **Rewards your way.** Money, items, commands, messages, broadcasts and sounds, on every vote, per site, first of the day, every Nth vote, streaks and totals, plus a vote party.
- **You won't feel it running.** Totals and leaderboards in memory, the database on its own thread, nothing repeating unless timed reminders are on.

Needs Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. Uses SQLite out of the box, or MySQL/MariaDB. Vault and PlaceholderAPI are optional.

**[Download on Modrinth](https://modrinth.com/plugin/xvote)** · [Docs](https://docs.xenastudios.co/xvote/) · [Setting up](https://docs.xenastudios.co/xvote/setting-up/) · [Commands](https://docs.xenastudios.co/xvote/commands/) · [Changelog](CHANGELOG.md)

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xvote:build       # compile, test, and check the jar's size budget
./gradlew :xvote:runServer   # a Paper server with xVote, in xvote/run/
XR_MYSQL=host:port:db:user:password ./gradlew :xvote:test   # also two servers on a real MySQL/MariaDB
```

```
api/                     xvote-api: the developer API, shipped inside the jar
src/main/java/co/xenastudios/xvote/
  XVote.java             the plugin: opens the database and reads every voter at startup, then sets up commands and modules
  receiver/              the Votifier receiver: both protocols, keys, tokens, limits, the NuVotifier import, and a client for /vote test
  vote/                  where every vote goes: matching names, never paying twice, totals and leaderboards in memory
  storage/               the tables, the one database thread, and imports from VotingPlugin and SuperbVote
  reward/                sites, rewards and triggers read from the config, and paying them on the right threads
  play/                  joins, quits and reminders
  hook/                  PlaceholderAPI, loaded only when installed
  command/               /vote and /xvote
boot-checks.txt          console commands CI runs on real Paper and Folia servers, and what each must log
CHANGELOG.md             one section per release; the release workflow publishes it
```

The shared core (reloading, config migration, the theme, the update checker, bStats, the database, the change feed) is in [`core/`](../core).

## Licence

[GPL-3.0-only](../LICENSE), like the rest of the repo.
