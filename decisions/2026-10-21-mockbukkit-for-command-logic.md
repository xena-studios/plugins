# MockBukkit for command logic, real servers for the rest (2026-10-21)

MockBukkit runs the commands' logic in milliseconds, but it can't `teleportAsync`, cancel Folia-style tasks, or tell harmful potion effects apart, and plugin classes it loads can't be `final`. Those paths are covered by `boot-checks.txt` on real servers, or by reading the code. CI also compiles against the newest Paper with removal warnings as errors (`-Pxena.newestPaper`), which is how `PotionEffectType#getCategory` (marked for removal in 26.3) was caught before release.
