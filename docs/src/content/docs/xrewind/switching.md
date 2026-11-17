---
title: Switching from CoreProtect
description: Move to xRewind and keep your history.
sidebar:
  order: 4
---

xRewind uses CoreProtect's command syntax, so `/co` habits carry over as `/xr`, and it can bring your CoreProtect history with it.

1. Install xRewind next to CoreProtect and start the server once. Leave CoreProtect where it is for now: xRewind reads its database through CoreProtect's own `config.yml`, whether that's SQLite (`database.db`) or MySQL.
2. From the console, run:

   ```
   /xr import coreprotect
   ```

   It runs in the background, with progress in the console, and logging carries on meanwhile. Big histories take a while: it reads CoreProtect's tables row by row and never lets xRewind's writer fall behind.
3. When it says it's done, stop the server and remove CoreProtect.

## What comes across

| From CoreProtect | In xRewind |
| --- | --- |
| Blocks broken, placed and clicked | Block history, with CoreProtect's rolled-back flags |
| Kills | Kills |
| Container transactions | Container history, by item type. Enchantments and custom names stay behind: CoreProtect stores them in its own format. |
| Logins and logouts | Sessions |
| Signs, chat, commands | Text history |
| Players and `#sources` | The same names, players with their uuids |

Item drops and pickups, and entity spawns, aren't imported.

Importing twice would duplicate everything, so a second `/xr import coreprotect` refuses, unless you add `#again`. That includes after an import that stopped partway (the server stopped, or CoreProtect's MySQL went away): what came across stays, so `#again` shows it twice. If that matters, `/xr purge` can't pick out imported history, so it's best to let an import finish.

## Commands, side by side

| CoreProtect | xRewind |
| --- | --- |
| `/co inspect` | `/xr inspect` |
| `/co lookup u:Steve t:1h` | `/xr lookup u:Steve t:1h` |
| `/co rollback u:Steve t:1h r:20` | `/xr rollback u:Steve t:1h r:20` |
| `/co restore ...` | `/xr restore ...` |
| `/co undo` | `/xr undo` |
| `/co purge t:30d` | `/xr purge t:30d` |
| `/co near` | `/xr near` |
| `#preview` | `#preview` |
