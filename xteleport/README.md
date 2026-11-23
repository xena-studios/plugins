![xTeleport by Xena Studios. A fuchsia map pin over the ring it lands in, and the xTeleport name over a stepped horizon, with the headline "Every teleport your server needs."](../.github/assets/xteleport-banner.png)

**Every teleport your server needs.**

xTeleport is homes, TPA, warps, spawn and random teleport in one small plugin, for Paper and Folia.

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **The whole set.** `/sethome`, `/home`, `/homes`, `/tpa`, `/tpahere`, `/tpaccept`, `/warp`, `/spawn`, `/rtp`, each one switchable, renamable and with its own permission.
- **Teleports that feel good.** Warmups that moving or damage cancels, on the action bar; cooldowns; Vault costs taken after the warmup and refunded if the teleport fails.
- **No deaths on arrival.** A home or warp now in lava or over the void is refused. The spot is loaded in the background to check it.
- **RTP that doesn't lag.** One candidate chunk at a time, loaded in the background and checked on its own region's thread, with a cap on searches at once and an option for pre-generated worlds.
- **Works with `/back`.** Every teleport uses the command cause, which xUtilities' `/back` records.
- **You won't feel it running.** Nothing repeats, listeners exist only for what's on, and homes are read and saved in the background.
- **Every change applies on reload**, everything but storage. No restarts.
- **Easy to switch to.** EssentialsX's command names, and its homes, warps and spawn imported with `/xteleport import essentials`.

**[Download](https://plugins.xenastudios.co/xteleport/download/)** · [Docs](https://plugins.xenastudios.co/xteleport/) · [Commands](https://plugins.xenastudios.co/xteleport/commands/) · [Switching from EssentialsX](https://plugins.xenastudios.co/xteleport/switching/) · [Developer API](https://plugins.xenastudios.co/xteleport/developer-api/) · [Changelog](CHANGELOG.md)

Needs Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer. Uses SQLite out of the box, or MySQL/MariaDB. Vault and PlaceholderAPI are optional.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xteleport:build       # compile, test, and check the jar's size budget
./gradlew :xteleport:runServer   # a Paper server with xTeleport, in xteleport/run/
```

```
api/                   the developer API (co.xenastudios.xteleport:xteleport-api), shipped inside the jar
src/main/java/co/xenastudios/xteleport/
  XTeleport.java       the plugin: opens the database, and in setup turns on its listeners and commands
  Api.java             the API, registered with the services manager
  teleport/            every teleport's steps: cooldown, cost, warmup, safe arrival, teleportAsync, refund
  home/                online players' homes in memory, and home limits
  tpa/                 pending requests, which run out when read
  warp/                warps and the spawn, in memory for everyone
  rtp/                 the random-spot search and its caps
  storage/             the tables, and the one thread that reads and writes them in order
  imports/             reading EssentialsX's homes, warps and spawn
  command/             the commands, and /xteleport's import and rtpcheck
  hook/                PlaceholderAPI, loaded only when installed
boot-checks.txt        console commands CI runs on real Paper and Folia servers, and what each must log
CHANGELOG.md           one section per release; the release workflow publishes it
```

The shared core (reloading, config migration, the theme, warmups, Vault, chat pages, offline players, the database) is in [`core/`](../core).

## Licence

[GPL-3.0-only](../LICENSE), like the rest of the repo.
