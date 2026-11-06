![xUtilities by Xena Studios. The wrench icon and the xUtilities name over a stepped horizon in amber, with "Every command you need. None you don't."](../.github/assets/xutilities-banner.png)

**Every command you need. None you don't.**

xUtilities gives every server the small commands it needs, whatever kind of server it is: gamemodes, flight, speed, `/top`, `/back`, heal and feed, inventory tools, workstation menus, and link commands like `/discord` that you write yourself. Install it on your lobby, survival and minigame servers alike, then switch off whatever a server doesn't use.

- **Every command can be turned off**, renamed with aliases, given its own permission, and reworded. A command that's off isn't registered at all, so it doesn't show up in tab completion and another plugin can have its name.
- **Every change applies on reload**, even turning commands on or off and adding link commands. No restarts.
- **Next to nothing to run.** Nothing runs on a timer but the daily update check and bStats, and you can turn both off. `/back`'s listener exists only while `/back` is on.
- **Paper and Folia**, from the same jar.

**[Download on Modrinth](https://modrinth.com/plugin/xutilities)** · [Docs](https://docs.xenastudios.co/xutilities/) · [Commands and permissions](https://docs.xenastudios.co/xutilities/commands/) · [Changelog](CHANGELOG.md)

Needs Paper 1.21.11 or newer (Purpur and Folia too) and Java 21 or newer.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xutilities:build       # compile, test, and check the jar's size budget
./gradlew :xutilities:runServer   # a Paper server with xUtilities, in xutilities/run/
```

```
src/main/java/co/xenastudios/xutilities/
  XUtilities.java   the plugin: declares the config and, in setup, the commands it turns on
  command/          the commands, grouped by kind (gamemodes, travel, menus, links, ...)
  config/           the config records; XConfig carries a 1.x config forward
boot-checks.txt     console commands CI runs on real Paper and Folia servers, and what each must log
CHANGELOG.md        one section per release; the release workflow publishes it
modrinth.md         the Modrinth project page, published when it changes on main
```

The shared core (reloading, config migration, the theme, the update checker, bStats) is in [`core/`](../core).

## Licence

GPL-3.0-only, like the rest of the repo. See [LICENSE](../LICENSE).
