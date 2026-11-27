# xBans on a proxy is made on its own thread, and logins wait for it (2026-11-27)

The Boot workflow's Velocity 3.4.0 cell failed xBans' startup: 159.7 ms against the 150 ms budget (`enable 143.2 ms`), with xSample at 121.4 and zChat at 107.6 in the same job (#127). Profiled in a Linux container on two cores (async-profiler, wall clock, from the plugin's enable), xBans' Velocity enable spent about 40% of its time on what zChat's doesn't do on Velocity's startup thread: reading and writing `config.yml` (its YAML parser not compiled yet), initialising the config schema and message catalogue, reading the jar's eight translations, and building the plugin with its commands. The rest was the core's `VelocityPlatform`, which zChat pays the same. The database already opened off the startup thread.

**What changed, on Velocity and BungeeCord.** Enable makes the platform and the player cache, and registers the listeners. Everything else (`config.yml`, the translations, `XBansPlugin`, its commands, the database, the update checker) is made on the plugin's own thread (`Where.Global`) right after, as zChat's relay does. Nothing moved on Bukkit or Fabric, and no class was added to Bukkit's startup.

**Nobody gets in during the gap.** This is the same fail-closed rule as before ([xBans 2.0](2026-11-26-xbans-2.md)), now covering one more step:
- A login waits, without blocking the proxy's threads, until the plugin is made and its bans are read, at most `WAIT_SECONDS` (10) in all. If it isn't ready by then, the player is told the server is still starting (`screens.not-ready`'s English, as there are no translations yet).
- If making the plugin fails, it's logged as an error and every login is refused.
- Connect and post-login refuse anyone who got past the login before the plugin was made.
- BungeeCord's login used to let a player in while `xbans` was null. It now refuses them.

Checked on Velocity 3.4.0 with the make delayed by 4 seconds: a banned player who joined 1 second after `Done` waited 3 seconds and got their ban screen, and a player who wasn't banned waited and was let through.

| Two-core container, `boot-proxy.sh --startup-budget 150`, medians of three boots, three runs each | xBans before | xBans after | zChat |
|---|--:|--:|--:|
| Velocity 3.4.0 (Java 21) | 66–75 ms | 35–48 ms | 40–58 ms |
| Velocity 4.3.1 (Java 25) | 68–74 ms | 28–29 ms | 50–56 ms |
| BungeeCord (Java 21) | 133–135 ms | 34–108 ms | 90–106 ms |

CI's runners measure about twice the container's numbers. The BungeeCord boots vary the most; what's left of its enable is the core's `BungeePlatform`, which zChat pays too. The Velocity and BungeeCord startup lists were recorded again (Velocity on 4.3.1): the database's classes and the plugin's own setup no longer load before the proxy is done.
