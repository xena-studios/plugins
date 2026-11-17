# xRewind changelog

## 1.0.0

The first release. Drop the jar in and start the server: it logs to SQLite in its own folder, with nothing to set up. Coming from CoreProtect? Run `/xr import coreprotect` to bring your history.

### Logging

- Blocks placed and broken, with what they held (a chest's items, a sign's text), and the torches, signs and rails that fall off with them.
- Explosions (TNT is put down to whoever lit it), fire, buckets and liquids that destroy something, pistons, and mobs that change blocks (endermen, withers, falling sand).
- Items put in and taken out of containers, kills, sign text, and joins and quits.
- Off unless you turn them on: hopper transfers, door and lever clicks, leaf decay, chat and commands.
- Each kind is a switch under `log`, and one that's off isn't even listened for. `excluded-worlds` leaves worlds out.
- Logging costs about 40 nanoseconds per change on the game's threads: everything else happens on one background thread, in batches.

### Looking up

- `/xr inspect`: click a block to see its history, or place one to see what was there.
- `/xr lookup` with CoreProtect's parameters: `u:` `t:` `r:` `a:` `i:` `e:` `w:` and `#count`. `/xr page` and `/xr near`.

### Rolling back

- `/xr rollback` and `/xr restore` with the same parameters, `#preview` to see the result first, and `/xr undo`.
- Blocks come back with what they held, and items taken from containers are put back.
- Blocks are set a few milliseconds per tick, chunk by chunk (region by region on Folia), so even a big rollback doesn't freeze the server.
- A rollback needs `t:`, covers 10 blocks around you without `r:`, and past 100 blocks needs `xrewind.rollback.global`.

### Keeping it tidy

- Automatic purges, free: history older than 30 days is deleted once a day (`purge.keep-days`; 0 keeps everything), and `/xr purge`.
- SQLite by default, MySQL or MariaDB for networks. No driver is bundled: Paper and Folia include them.
- `/xr import coreprotect` reads CoreProtect's database (SQLite or MySQL) and imports blocks, kills, containers, sessions, signs, chat and commands.
- `/xr status` shows where the history goes and whether logging is keeping up.
