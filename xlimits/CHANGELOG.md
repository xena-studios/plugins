# xLimits changelog

## 1.0.0 (unreleased)

Not released yet: 1.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release).

The first release: farm, entity and redstone limits on **Paper, Folia, Spigot and CraftBukkit from 1.13 to 26.3**, and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), in eight languages.

### Added

- **Entity caps per chunk**, by group (`animals`, `monsters`, `villagers`, `water`, `ambient`, `golems`, `vehicles`, `armor_stands`, `item_frames`), by type, or by groups of your own. Past a cap, new ones don't come (natural spawns, spawners, breeding, eggs, spawn eggs, dispensers, placing; each route a switch), and nothing already there is removed.
- **Farm slowing:** a group past its threshold in one chunk loses collisions, AI or breeding, and goes back to normal as the count falls. Named, tamed and leashed mobs and villagers are left alone; slowed mobs carry tags, so a restart never strands them.
- **Block caps per chunk** on any block or group of blocks; placing past one is refused with a message.
- **Redstone clocks** broken past a rate, **primed TNT and falling blocks** per chunk, **entities through portals** per few seconds, and **pets per player**, each off by default.
- **Load profiles:** stricter limits while players online, TPS or MSPT pass a threshold, with hysteresis and a minimum time; per region on Folia.
- **Messages and alerts:** players are told why; staff get one alert per chunk per few minutes with a teleport button and, with xRewind, "who built this".
- **`/xlimits here`, `top` and `near`** to find the problem, with the server's own settings beside xLimits' counts; **`/xlimits restore`** before removing the plugin.
- **Limits per world** and per area (other plugins, through the API), and a bypass permission.
- **Switching:** `/xlimits import farmcontrol` and `insights`.
- **For other plugins:** a developer API with counts, limits, limit providers and events (`LimitReachedEvent`, cancellable to allow, `FarmSlowedEvent`, `FarmRestoredEvent`, `ProfileChangedEvent`), also as Bukkit events.
- `/xlimits reload`, `debug` and `import`.
