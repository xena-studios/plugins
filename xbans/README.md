![xBans by Xena Studios. A tangerine pixel gavel icon and the xBans name over a stepped horizon in tangerine, with the headline "Bans, mutes and warnings, the LiteBans way."](../.github/assets/xbans-banner.png)

**Bans, mutes and warnings, the LiteBans way.**

xBans is a free, open-source punishment plugin with LiteBans' commands, flags and durations: bans, temporary and IP bans, mutes, warnings and kicks, with history, alts, templates and staff limits.

- **Your staff already know it.** `/ban Steve -s 7d server:survival Hacking`, `/tempmute Alex 1d2h`, `/unban #123`, `/history`, `/alts`.
- **Bans that hold.** What's in force is in memory before anyone can join, so the join and chat checks never ask the database. Mutes cover chat (on the older chat events too), the listed commands by any alias, signs, books and anvil renames.
- **Nothing is ever deleted.** An unban is a record beside the ban; only `/prunehistory` deletes.
- **Networks without a proxy plugin.** Servers sharing a MySQL database share bans within seconds.
- **IPs can be hashed**, so alts and IP bans work with no list of addresses to leak.
- **One-command imports** from LiteBans, EssentialsX and vanilla.

Needs Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. Uses SQLite out of the box, or MySQL/MariaDB.

**[Download on Modrinth](https://modrinth.com/plugin/xbans)** · [Docs](https://docs.xenastudios.co/xbans/) · [Commands](https://docs.xenastudios.co/xbans/commands/) · [Switching from LiteBans](https://docs.xenastudios.co/xbans/switching/) · [Changelog](CHANGELOG.md)

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xbans:build       # compile, test, and check the jar's size budget
./gradlew :xbans:runServer   # a Paper server with xBans, in xbans/run/
```

```
api/                 the developer API (co.xenastudios:xbans-api), shipped inside the jar
src/main/java/co/xenastudios/xbans/
  XBans.java         the plugin: opens the database, and in setup turns on enforcement and the commands
  Punisher.java      makes and revokes punishments for commands, the API and the network alike
  model/             what's in force, in memory; punishments as kept; lengths as staff type them
  storage/           the tables, the database thread, and every query (keyset history pages)
  enforce/           the join checks, the mute checks, and online players' IP keys
  command/           the commands, LiteBans-style argument parsing, and who a name or #id means
  lookup/            placeholders for messages, and history page cursors
  ip/                IP keys (raw or salted hashes) and the alt alert
  network/           other servers' changes, from the core's change feed
  importer/          vanilla, EssentialsX and LiteBans
boot-checks.txt      console commands CI runs on real Paper and Folia servers, and what each must log
CHANGELOG.md         one section per release; the release workflow publishes it
```

The shared core (reloading, config migration, the theme, the update checker, bStats, the database and its change feed, offline players and chat pages) is in [`core/`](../core).

## Licence

[GPL-3.0-only](../LICENSE), like the rest of the repo.
