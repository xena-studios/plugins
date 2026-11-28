# The heap budget is what a plugin's class loader alone keeps alive, 2.4 MB (2026-11-28)

The runtime budgets' heap figure (`scripts/budgets/check.sh`, `heap-mb` in `gradle/perf-budgets.txt`) was the used heap of a Paper server with the plugin, less that of the same Paper without plugins, each after full GCs. #258 made each side the lowest of three readings, which steadied the readings within a process, but the plugin-less server itself moves by about 2 MB from boot to boot (234.9–236.5 MB on CI, 240.7–242.5 locally), so the same jar read anywhere in a 2–3 MB range, and xMarket (4.69), xRewind (4.27) and xStats (4.29) failed the 4 MB budget with no change of theirs.

**What's measured now.** After the heap and metaspace readings, `check.sh` takes a heap dump of the measured boot (`jcmd GC.heap_dump`, live objects after a full GC). Once the server has stopped, `scripts/budgets/Retained.java` reads it (single-file Java, run with the same JDK, about 3 seconds for a 400 MB dump) and the dump is deleted. Each plugin is its main class's class loader, every class that loader defined (hidden lambda classes included) and every instance of those classes. What the plugin retains is what the GC roots reach that they don't reach once those are taken away. That includes its own objects, its classes' mirrors and statics, the libraries and data only it holds, and on Paper its `libraries:` loader with the Kotlin stdlib's classes. Sizes are the JVM's with compressed oops (12-byte header, 4-byte references, 8-byte alignment). Weak, phantom and finalizer references aren't followed; soft ones are, since a server with a free gigabyte keeps them. This is #272's analyzer, made into a check. It's seeded by the main class's loader, not a package prefix, so several plugins in one run are measured apart rather than sharing the difference equally.

The process difference is still reported in the report's heap section, but it isn't budgeted.

**Weighed and not taken: more boots per side, comparing medians or minimums.** Each extra boot of either server adds to every plugin's job. The spread is 2 MB, so getting within ~0.3 MB would take many boots per side. That would multiply the job's run time for a figure that would still include the server's own per-plugin cost.

| CI, Paper 26.3, two cores | Before: the difference, 8–13 past runs (min–max) | The difference in the 3 runs below | **Now: retained, 3 runs** |
|---|--:|--:|--:|
| xMarket | 1.54–3.34 | 2.42–2.99 | 1.664, 1.666, 1.666 |
| xPlots | 1.52–3.62 | 2.03–2.43 | 1.096, 1.092, 1.093 |
| xBans | 1.06–4.20 | 1.69–2.73 | 0.965, 0.965, 0.965 |
| xTeleport | 1.07–3.87 | 1.25–3.82 | 0.929, 0.930, 0.929 |
| xVote | 0.86–3.51 | 0.61–2.39 | 0.873, 0.872, 0.871 |
| xBackup | 0.22–3.11 | 1.01–1.74 | 0.841, 0.841, 0.841 |
| xRewind | 1.09–3.76 | 1.67–4.88 | 0.800, 0.800, 0.803 |
| xMoney | 0.49–3.35 | 1.43–2.32 | 0.786, 0.788, 0.786 |
| xUtilities | 0.61–3.54 | 0.58–2.73 | 0.781, 0.781, 0.780 |
| zChat | 0.40–2.33 | 1.40–1.93 | 0.720, 0.720, 0.718 |
| xLimits | 0.88–2.33 | 1.17–1.99 | 0.710, 0.708, 0.709 |
| xStats | 1.26–2.95 | 1.63–2.55 | 0.673, 0.673, 0.673 |
| xLimbo | 1.03–2.37 | 1.52–1.72 | 0.658, 0.656, 0.659 |
| xVanish | 0.62–2.41 | 1.06–2.12 | 0.622, 0.620, 0.623 |
| xSample | 0.01–2.75 | 0.81–0.88 | 0.430, 0.438, 0.438 |

All figures are in MB. On Paper 1.21.11 with Java 21 (the nightly BotMark cell), xSample reads 0.861, 0.869 and 0.861. Locally (M1 Pro, Paper 26.3, other servers booting beside it), the figure repeated to the kilobyte: xStats 0.893 three times, xMarket 1.667 three times, xSample 0.441, 0.440 and 0.441. Over the same boots, the difference read 2.22–3.80, 3.12–3.69 and 2.07–2.77. The budgets job takes the same 2–3 minutes per plugin as before.

**A real regression is caught.** A throwaway xSample build that keeps a 2 MiB `ByteArray` from enable read 2.442 MB twice, against the clean build's 0.441 and 0.440: exactly the array, so it fails 2.4 MB. The old difference read that build at 5.69 and 4.51, and the clean build at up to 2.96.

**The budget is 2.4 MB, not ROADMAP's first 4 MB, because the figure measures something narrower.**
- The old figure also counted what the server keeps for any plugin: Paper's Maven resolver set up for `libraries:`, the plugin manager's entries, the class loaders' lock maps, and the listener and command wrappers around the plugin's objects. Over the 45 readings above, that share was 0.94 MB at the median.
- It also counted the server's ~2 MB of noise. So under the old 4 MB, a plugin could keep only about 2–3 MB of its own before failing on a bad boot.
- On this figure, 2.4 MB keeps that allowance and makes the ask concrete: a plugin keeping 2 MB more fails, the lightest one too (xSample's 0.43 + 2 is over). The heaviest, xMarket, has 0.7 MB of room.
- A plugin that needs more says why in its decision file, as with the jar budget.

**What it doesn't see.** Data a plugin hands to the server is reachable without its loader, so it isn't counted: scoreboard teams, boss bars, entities' persistent data, a world it creates (which ROADMAP's heap budget leaves aside anyway). Neither is data held only by a local variable on a running thread's stack. The reported process difference still shows a large leak of that kind. The heap-growth budget across the load is unchanged.

**Files.** `scripts/budgets/Retained.java` (new), `check.sh` (`dump` and `retain`, plus each plugin's main class read from its plugin.yml or paper-plugin.yml), `analyze.py` (a heap check per plugin, the top retained types in the report, `heap.retained_mb` in results.json), `gradle/perf-budgets.txt`, ROADMAP.md's budget row, and xLimits' performance page.
