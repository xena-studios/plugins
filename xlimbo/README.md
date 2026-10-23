![xLimbo by Xena Studios. The purple cube icon and the xLimbo name over a stepped horizon in purple, with "Never drop a player again."](../.github/assets/xlimbo-banner.png)

**Never drop a player again.**

xLimbo turns a small Paper server into your network's fallback. When your main server crashes, restarts or can't be reached, your proxy sends players to xLimbo instead of disconnecting them. They wait in an empty void world, in creative with flight, on a purple glass floor, then go back with `/join`, or on their own the moment the main server is back.

- **Built for everyone arriving at once.** Players are placed before they're in the world, on land that's already loaded, so a whole network landing at once never waits on world generation. Nothing is saved to disk.
- **Sends everyone back, without a stampede.** With server-check on, xLimbo watches your main server and sends players back as soon as it's up, a few per second, showing each player their place in line.
- **It stays up.** Every feature is wrapped so a failure is logged and skipped, never taking the server down. A bad config value falls back to its default.
- **Nobody can break it.** The floor can't be broken, players who fall are caught, and portals, liquids, fire, explosions, redstone, mobs and dropped items are all blocked. Each protection can be turned off, and one that's off costs nothing.
- **Every change applies on reload**, even the world's name and floor.

**[Download on Modrinth](https://modrinth.com/plugin/xlimbo)** · [Docs](https://docs.xenastudios.co/xlimbo/) · [Network setup](https://docs.xenastudios.co/xlimbo/network-setup/) · [Changelog](CHANGELOG.md)

Needs Paper 1.21.11 or newer (or Purpur) and Java 21 or newer. Not for Folia: Folia can't create worlds, and a limbo has nothing for Folia to spread across threads.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xlimbo:build       # compile, test, and check the jar's size budget
./gradlew :xlimbo:runServer   # a Paper server with xLimbo, in xlimbo/run/
```

```
src/main/java/co/xenastudios/xlimbo/
  XLimbo.java      the plugin: declares the config and, in setup, the features it turns on
  world/           the void world, arrivals, the action bar and hiding players from each other
  network/         the proxy connection, the return queue and the status ping to the main server
  protect/         the protections
  config/          the config records; XLConfig carries a 1.x config forward
boot-checks.txt    console commands CI runs on real Paper servers, and what each must log
CHANGELOG.md       one section per release; the release workflow publishes it
modrinth.md        the Modrinth project page, published when it changes on main
```

xLimbo only ever deletes or rebuilds a world folder carrying its `xlimbo.dat` marker; keep it that way ([DECISIONS.md](../DECISIONS.md) explains why). The shared core (reloading, config migration, the theme, the update checker, bStats) is in [`core/`](../core).

## Licence

GPL-3.0, like the rest of the repo. See [LICENSE](../LICENSE).
