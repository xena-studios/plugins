![xUtilities by Xena Studios. An amber pixel wrench icon and the xUtilities name over a stepped horizon in amber, with the headline "Every command you need. None you don't."](../.github/assets/xutilities-banner.png)

**Every command you need. None you don't.**

xUtilities is a lightweight essentials plugin for Paper and Folia: gamemodes, flight, `/back`, heal, workstations anywhere and your own link commands, each one switchable.

- **Every feature free.** No premium version, no early-access builds.
- **Only what you want.** Every command can be turned off, renamed, given its own permission and reworded. A command that's off isn't registered at all, so another plugin can have its name.
- **You won't feel it running.** Nothing runs on a timer but the daily update check and bStats, and `/back`'s listener exists only while `/back` is on.
- **Every change applies on reload**, even turning commands on or off and adding link commands. No restarts.

**[Download on Modrinth](https://modrinth.com/plugin/xutilities)** · [Docs](https://plugins.xenastudios.co/xutilities/) · [Commands](https://plugins.xenastudios.co/xutilities/commands/) · [Developer API](https://plugins.xenastudios.co/xutilities/developer-api/) · [Changelog](CHANGELOG.md)

Needs Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xutilities:build       # compile, test, and check the jar's size budget
./gradlew :xutilities:runServer   # a Paper server with xUtilities, in xutilities/run/
```

```
api/                the developer API (co.xenastudios.xutilities:xutilities-api), shipped inside the jar
src/main/java/co/xenastudios/xutilities/
  XUtilities.java   the plugin: declares the config and, in setup, the commands it turns on
  Api.java          the developer API's implementation, registered while the plugin is enabled
  command/          the commands, grouped by kind (gamemodes, travel, menus, links, ...)
  config/           the config records; XConfig carries a 1.x config forward
boot-checks.txt     console commands CI runs on real Paper and Folia servers, and what each must log
CHANGELOG.md        one section per release; the release workflow publishes it
modrinth.md         the Modrinth project page, published when it changes on main
```

The shared core (reloading, config migration, the theme, the update checker, bStats) is in [`core/`](../core).

## Licence

[GPL-3.0-only](../LICENSE), like the rest of the repo.
