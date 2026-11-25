# xRewind's write path: references in, numbers out (2026-11-17)

A listener does as little as it can: it builds one record from what the event already has (the player's uuid and name, the world's name, the block's `BlockData` before and after) and adds it to a lock-free queue. It looks nothing up and takes no lock, so it's as cheap on Folia's region threads as on Paper's main thread: about 40 ns in the benchmark (`WriterTest`), against a budget of 2 µs.

One writer thread does the rest. It owns the dictionaries that turn names, worlds and block states into ids, as plain maps, since nothing else touches them, and writes batches of up to 10,000 rows, each one transaction, every half second or as soon as 2,000 are waiting. New dictionary rows go in the same transaction as the first rows that use them, so no row ever points at a name that isn't there. A batch that fails is kept and retried on a fresh connection, after a pause that grows to 30 seconds; the console hears about it once, not on every retry. Until the database is ready, or while it's down, entries wait in the queue: the console is warned at 100,000, and past a million new ones are counted and dropped rather than run the server out of memory. Stopping writes what's left, for up to 10 seconds.

A batch that keeps failing on a live connection (three times in a row) is a row the database won't take, not the database being down, so its rows are then written one at a time and only that one is dropped, with a warning; `/xrewind status` counts it as missed. MySQL tables are made utf8mb4 whatever the database's default, so a heart on a sign or an emoji in chat is never that row.

`BlockData` is safe to read from the writer: the server's block states are immutable, and `getBlockData` hands back a copy.

Plugins can now add subcommands to their own command (`Setup.subcommand`): `/xrewind status` sits beside `reload` and `info`, each with its own permission, so staff who aren't admins can use the ones they're given.
