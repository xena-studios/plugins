<!-- modrinth_exclude.start -->
A draft of xLimits' Modrinth and Hangar page ("xLimits: Farm, Entity & Redstone Limits"), kept
out of the Store pages workflow (which publishes every */modrinth.md that changes on main)
until the release: then the brand repo has xLimits' banner and badges, this file is renamed
modrinth.md, and `pnpm catalog` fills in its generated parts.
<!-- modrinth_exclude.end -->

**Farm, entity and redstone limits, on Paper, Folia, Spigot and Fabric.**

xLimits caps and slows the things that overload a server instead of wiping entities on a timer, tells players why, and shows staff where the load is. Limits and diagnostics, not a "lag fixer": nothing is ever deleted.

- **Caps per chunk that remove nothing.** Animals, monsters, villagers, vehicles, armour stands, item frames, or any type. Past a cap, new ones don't come, from spawners, breeding, spawn eggs, dispensers and placing too, which Paper's own limits don't cover.
- **Crammed farms slowed, not killed.** No collisions, no AI or no breeding past a threshold, back to normal as the farm empties. Named, tamed and leashed mobs and villagers are left alone.
- **Block caps.** Hoppers, spawners, pistons, observers, anything, per chunk, with a message saying why.
- **Redstone clocks, TNT, falling blocks, portals and pets**, each a switch.
- **Load profiles.** Stricter limits while the server is busy, from players online, TPS or MSPT, with hysteresis. On Folia, each region its own.
- **Find the problem.** `/xlimits here` shows your chunk against every limit and Paper's settings beside them; `/xlimits top` lists the busiest chunks; staff alerts come with a teleport and an xRewind "who built this".
- **A way back.** Slowed mobs carry a marker; `/xlimits restore` puts every one back.
- **No repeating tasks, no database**, and listeners only for what's on.
- **Easy to switch to** from FarmControl and Insights, with one command.
- **In your players' languages**: English, German, Spanish, French, Brazilian Portuguese, Russian, Polish and Simplified Chinese.

**[Docs](https://plugins.xenastudios.co/xlimits/)** · [Recommended Paper settings](https://plugins.xenastudios.co/xlimits/paper-settings/) · [Switching from FarmControl](https://plugins.xenastudios.co/xlimits/switching/) · [Developer API](https://plugins.xenastudios.co/xlimits/developer-api/)

Runs on Paper, Folia, Spigot and CraftBukkit from 1.13, and Fabric from 1.20.1 (server-side only). xRewind is optional.
