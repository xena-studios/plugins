# The nightly load run reports startup instead of failing on it (2026-11-25)

The BotMark load (10 mineflayer bots for a minute) runs nightly on Paper 1.21.11, because mineflayer can't join 26.x. Its startup measure kept failing at about 208 ms against the 150 ms first-plugin budget, while xSample's startup on Paper 26.3 in the same job passes at about 105 ms.

The time isn't ours. In a two-core container that tracks CI's runners, the same jar takes about 210–230 ms on 1.21.11 against 57 ms on 26.3, on Java 21 and 25 alike, and a JFR timeline shows the server thread spends only 6–7 ms of CPU in our enable on both: the rest it waits for a core while Paper 1.21.11's loader and the JIT's compiler threads run. The one cost that was ours, parsing all of `paper-global.yml` for Velocity's secret, went in #178 ("A lighter core").

So that one step passes `--report-only startup-first-ms,startup-ms`: its startup is written to the report and the job summary as a warning. Startup stays a failing budget for every rewritten plugin on the newest Paper in the same job, and the load run's own budgets (ticks under load, heap, metaspace, idle) still fail it. If mineflayer learns 26.x, the load run moves there and startup fails again.
