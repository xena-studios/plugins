---
title: Switching from SuperVanish
description: "Move from SuperVanish or PremiumVanish to xVanish: the same commands, your permissions, and who's vanished, imported."
sidebar:
  order: 8
---

1. Stop the server, take SuperVanish's (or PremiumVanish's) jar out of `plugins/` and put xVanish's in. Leave its folder: the import reads it.
2. Start the server and run `/xvanish import supervanish --dry-run` (or `premiumvanish`): it says how many vanished players it found. Then `/xvanish import supervanish` adds them to `vanished.txt`, so they're vanished when they next join.
3. Keep your permissions: set `vanish.other-plugins-permissions: true` and `sv.use`, `sv.see` and the rest grant xVanish's. Or move to `xvanish.*` when you're ready.

`/sv on`, `/sv off`, `/sv list`, `/sv login` and `/sv logout` work as before (`/sv` and `/pv` are `/vanish`'s aliases).

**What's different.** Silent containers are read-only: Bukkit has no way to open a real chest without its lid moving, and xVanish uses no packets or NMS. Sneak to open one as usual. PremiumVanish's other settings aren't imported: xVanish's switches are in its own `config.yml`.
