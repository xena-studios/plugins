# A real client in the boot tests: one mineflayer bot on Spigot 1.8.8 and Paper 1.21.11 (2026-11-24)

Until now no client had joined a boot test, so messages to players, joins and quits, languages and tab completion were only unit-tested (#162). Now one does, on every pull request that touches Bukkit.

- **`client:`, a step of `scripts/boot-test.sh`,** runs `scripts/budgets/bots/client.js` with the server's port and waits for it: one mineflayer bot from the budget check's packages (installed into `.cache/budget-bots/` the same way), on whatever version the server speaks (mineflayer asks it first). It joins as `Joiner` with its client in German and checks, from the client's side:
  - `/xsample` answers the player;
  - `/pay ` completes to the bot's own name, and `/xsample ` to its subcommands;
  - `/xsample debug` replies in German (`Prüfe xSample...`, a core message drawn by `AdminText` from the client's locale);
  - after the client switches to English, the same command replies in English.

  It prints `CLIENT PASS` or `CLIENT FAIL`, and the step fails on anything but a pass. The bot is an operator through a seeded `ops.json` (`scripts/boot-seeds/client`, the offline UUID of its name), so nothing has to be typed into the console while it plays.
- **The server's side is xSample's `PlatformCheck`:** 3 s after a player joins, the family's player cache must have them online in German (`CLIENT JOIN PASS`); as they quit, offline, and in English where the server reports a client's language change (1.12 on; on 1.8.8 only the join's language is read). `core/core-testing/sample/bukkit/client-checks.txt` waits for both lines.
- **Where it runs:** a new per-PR cell, Paper 1.21.11 (the newest Minecraft mineflayer 4.39 speaks; CI's newest Paper is 26.3), and the Spigot 1.8.8 cell, both with `client: true`, which adds Node.js and the bots' cache and the client checks to the cell's boot. Locally the client added about 15 s to a boot (22 s for the whole Spigot 1.8.8 boot, about 30 s for Paper with its paused boot), well under the two minutes a per-PR check may cost, so it isn't nightly-only.
- **Passed locally:** Paper 1.21.11 (Java 21) and Spigot 1.8.8 (Java 8), every check on both.
- **Not yet:** a client on Fabric (mineflayer could join a Fabric server, which needs nothing on the client), the client command tree after a reload (`updateCommands`, which DECISIONS' commands entry still wants confirmed), and Folia, where mineflayer's 26.x support is what's missing.
