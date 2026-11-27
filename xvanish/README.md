# xVanish

**Vanish for staff, on Paper, Folia, Spigot and Fabric.** Not released yet: 1.0 ships with the other plugins' rewrites ([ROADMAP.md](../ROADMAP.md), Release); its docs are built, hidden, at `/xvanish/next/`.

xVanish hides staff from players everywhere a player could notice them: the world and the tab list, join and quit messages (with fake ones), the server list, the locator bar, death and advancement messages, mobs, pickup, pressure plates, sculk, collisions, projectiles and raids, and every Xena plugin. Silent containers, levels, flight, night vision and a boss bar; vanish survives relogs and restarts; networks share it through one database. No repeating tasks, no packets or NMS on Bukkit, and listeners only for the switches that are on.

[Changelog](CHANGELOG.md) · [Why it's built this way](../decisions/2026-11-27-xvanish-1.md)

## Developing

| Folder | |
| --- | --- |
| `api/` | The developer API (`XVanish.api()`), platform-neutral; its signatures in `api/api/`. |
| `common/` | Everything xVanish does, for every platform: who's hidden from whom, the commands, the config and messages, `vanished.txt`, network mode. Kotlin Multiplatform, built for the JVM and `wasmWasi`. |
| `bukkit/` | Paper, Folia, Spigot and CraftBukkit, 1.8.8 on: Bukkit's `hidePlayer`, a listener per switch, PlaceholderAPI, the Bukkit events. |
| `fabric/` | The Fabric mod, a branch of `core/platform-fabric`'s build. |

```sh
./gradlew :xvanish:bukkit:assemble
xvanish/bukkit/boot.sh paper 26.3 --java <JDK 25 home>
xvanish/bukkit/boot.sh paper 1.21.11 --family --java <JDK 21 home> --seed scripts/boot-seeds/client --checks xvanish/bukkit/client-checks.txt
./gradlew -p core/platform-fabric :xvanish:26.3:collect
scripts/boot-fabric.sh --version 26.3 --checks xvanish/fabric/boot-checks.txt core/platform-fabric/build/libs/xvanish-fabric-*+26.3.jar
```
