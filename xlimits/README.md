# xLimits

**Farm, entity and redstone limits, on Paper, Folia, Spigot and Fabric.** Not released yet: 1.0 ships with the other plugins' rewrites ([ROADMAP.md](../ROADMAP.md), Release); its docs are built, hidden, at `/xlimits/next/`.

xLimits caps and slows what overloads a server instead of deleting it on a timer: entity caps per chunk that refuse new spawns, breeding, spawn eggs and placing and remove nothing; crammed farms slowed (no collisions, no AI, no breeding) and restored as they empty; block caps; redstone clocks, TNT, falling blocks, portals and pets; load profiles that tighten while the server is busy, per region on Folia. Players are told why, staff are alerted with a teleport, and `/xlimits here`, `top` and `near` show where the load is. No repeating tasks, no database, listeners only for the limits that are on.

[Changelog](CHANGELOG.md) · [Why it's built this way](../decisions/2026-11-27-xlimits.md)

## Developing

| Folder | |
| --- | --- |
| `api/` | The developer API (`XLimits.api()`), platform-neutral; its signatures in `api/api/`. |
| `common/` | Everything xLimits decides, for every platform: the rules, the counts per chunk, farm slowing, load profiles, the commands, config and messages, the importers. Kotlin Multiplatform, built for the JVM and `wasmWasi`. |
| `bukkit/` | Paper, Folia, Spigot and CraftBukkit, 1.13 on: the core's world hooks for spawns, blocks and tames, listeners of its own for chunks, redstone, TNT and portals, the Bukkit events. |
| `fabric/` | The Fabric mod, a branch of `core/platform-fabric`'s build. |

```sh
./gradlew :xlimits:bukkit:assemble
xlimits/bukkit/boot.sh paper 26.3 --java <JDK 25 home>
xlimits/bukkit/boot.sh "$(scripts/server-jar.sh spigot 1.13.2 | sed -n 's/^jar=//p')" --java <JDK 11 home>
./gradlew -p core/platform-fabric :xlimits:26.3:collect
scripts/boot-fabric.sh --version 26.3 --checks xlimits/fabric/boot-checks.txt core/platform-fabric/build/libs/xlimits-fabric-*+26.3.jar
```
