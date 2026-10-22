# xLimbo changelog

## 2.0.0

A rewrite. Replace the jar and restart: your 1.x config is carried forward, and the old file is kept as `config.yml.bak-v1`. xLimbo 2 takes over the world 1.x made, builds and all if you kept it saved.

**Needs Paper 1.21.11 or newer** (or Purpur) and Java 21. 1.x ran on 1.18.2 and newer. Still not for Folia, which can't create worlds.

### New

- **Send everyone back when the main server is up.** With `server-check` on, xLimbo asks your main server for its status every few seconds, and sends players back the moment it's fully started.
- **A paced return.** Automatic returns go out a few players a second (`queue.per-second`), so the main server isn't flattened the moment it's back, and each player sees their place in line on the action bar.
- A player the main server keeps refusing, such as one banned there, is sent back automatically at most three times in five minutes, so they aren't bounced forever.
- The console says when the world is ready, and how long its spawn area took to load.
- A `theme` at the top of the config: a prefix and four colours xLimbo's messages use. If you never changed a 1.x message, it gets the new look; any you changed are kept.
- An update checker, and [bStats](https://bstats.org/plugin/bukkit/xLimbo/34867) anonymous usage statistics. See [Privacy](https://docs.xenastudios.co/start/privacy/) for exactly what each sends, and how to turn them off.

### Faster arrivals

- Players can't see each other by default (`player.hide-others`). With hundreds arriving in one spot, everyone tracking everyone else is a limbo's biggest load.
- Players are placed before they're in the world, rather than teleported straight after joining, so each arrival loads one spot, once.
- The land where players arrive is kept loaded, so a whole network landing at once never waits for world generation.
- Arrivals spread up to 64 blocks from the centre by default, rather than 4000. 1.x's spread gave nearly every player fresh land to generate and send. A 1.x config still on 4000 gets 64; one you changed is kept.
- No weather, and no advancement messages.

### Everything applies on reload

- `/xlimbo reload` now applies every setting, `/join`'s aliases included.
- Changing the world's `name`, `floor-block` or `floor-y` rebuilds the world on reload: players are moved out for a moment and put back.
- A config file that isn't valid YAML no longer loses your settings: xLimbo says where the error is and keeps running with the settings it had (or the defaults, at startup) until it's fixed.

### Changed

- The floor is purple stained glass by default, easier to see against the void. A 1.x config still on plain glass gets purple; any other block you chose is kept.
- `floor-block` takes a block as Minecraft names it, such as `white_stained_glass`; 1.x's `GLASS` still works.
- With `auto-save: false`, the world is deleted and made fresh on every startup, so nothing is ever kept on disk.
- With server-check on, players go back as soon as the main server is up, and `auto-join` isn't used.
- xLimbo marks the worlds it makes, and only ever deletes or rebuilds one it marked. A world of the same name it didn't make is left alone.
- The floor must be a solid block that doesn't fall. It can't be blown up, even with explosions allowed, and it doesn't melt or decay.
- The stray-entity sweep leaves armour stands, item frames, paintings, display and marker entities, and anything tagged `xlimbo.keep`.
- `/join` can be turned off.
- Protections that are off no longer listen for events at all.
