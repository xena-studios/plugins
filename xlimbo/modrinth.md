![xLimbo by Xena Studios. A pixel cube icon and the xLimbo name over a stepped horizon in purple, with the headline "Never drop a player again."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xlimbo/banner.png)

## Never drop a player again.

Your main server crashes. Restarts. Goes down for an update. Without a fallback, every player on it gets kicked, and some of them don't come back.

**With xLimbo, they don't get kicked.** Your proxy sends them to xLimbo instead: a calm, empty world where they wait in creative with flight on, until your server is back and they go back with `/join`, or automatically the moment it's up.

**[Read the docs](https://docs.xenastudios.co/xlimbo/)** · **[Network setup](https://docs.xenastudios.co/xlimbo/network-setup/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **It stays up when everything else is down.** That's its whole job. Every feature is built so that a failure is logged and skipped, never taking the server down, and a bad config value falls back to its default.
- **Built for everyone arriving at once.** Players are placed before they're in the world, on land that's already loaded, so a whole network landing at once never waits on world generation. The world is a single floor and nothing else, a world border caps how far it can grow, and nothing is ever saved to disk.
- **Sends everyone back, without a stampede.** Turn on server-check and xLimbo watches your main server, then sends players back as soon as it's up, a few per second, showing each player their place in line.
- **Nobody can break it.** The floor can't be broken, players who fall are caught, and portals, liquids, fire, explosions, redstone, mobs and dropped items are all blocked. Turn off any protection you don't want.
- **Quiet.** No join, quit or death spam when your whole server lands here at once.
- **Every change applies on reload**, even the world's name and floor.
- **It's a real Paper server**, so your other plugins, permissions and proxy setup all still work.

---

## What players see

- A welcome message telling them what happened, and a reminder in the action bar.
- An empty void with a purple glass floor, in creative with flight, so there's something to do while they wait.
- `/join` (or `/server`, `/lobby`) to go back the moment your server is up. With server-check on, they're sent back on their own, with their place in line on screen.

Every message is yours to rewrite, with full MiniMessage colours and formatting.

---

## Up and running

1. Run a small separate Paper server with xLimbo on it.
2. Add it to your proxy after your main server. With Velocity:

        [servers]
        main = "127.0.0.1:25566"
        limbo = "127.0.0.1:25567"
        try = ["main", "limbo"]

3. Set `main-server` in `plugins/xLimbo/config.yml` to your main server's name in the proxy, and turn on `server-check` so players go back on their own.
4. Stop your main server with someone online, and watch them land in xLimbo instead of being kicked.

Velocity, BungeeCord, and keeping `/join` secure: **[docs.xenastudios.co/xlimbo](https://docs.xenastudios.co/xlimbo/)**

**Requires** Paper 1.21.11 or newer (or Purpur), Java 21, and a proxy (Velocity or BungeeCord). Not for Folia.

**Coming from xLimbo 1.x?** Replace the jar and restart. Your config is carried forward, and the old file is kept as `config.yml.bak-v1`.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0 · [Source](https://github.com/xena-studios/plugins/tree/main/xlimbo)
