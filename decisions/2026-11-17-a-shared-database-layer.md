# A shared database layer, with no drivers shaded in (2026-11-17)

xRewind keeps its history in a database, and the voting and economy plugins now do too, so `core/db` opens one from a shared `storage` config section: SQLite in the plugin's folder by default, MySQL or MariaDB for networks.

- **No driver in the jar.** Paper and Folia ship sqlite-jdbc and mysql-connector-j (checked on 1.21.11 and 26.3), and a paper-plugin's class loader sees them. Shading them would add about 14 MiB. If Paper ever drops them, a `PluginLoader` can fetch them at startup.
- **SQLite is set up for one writer and many readers:** WAL, so lookups never wait for the writer; `synchronous=NORMAL`, safe with WAL (a power cut can lose the last moments, never corrupt the file); `auto_vacuum=INCREMENTAL`, so purges give space back a little at a time instead of locking the file for a full `VACUUM`.
- **Numbered schema steps**, recorded in a `meta` table, each in its own transaction. A database made by a newer version is refused, never changed.
- **Connections on demand, no pool.** A plugin that writes a lot keeps one connection for its writer; reads are staff commands, rare enough to open their own.

xRewind opens its database off the server's threads, so a slow or unreachable MySQL never holds up startup or a reload, and a reload that can't open a new database keeps the old one. Its rows are only numbers: names, worlds, block states and items are stored once in dictionary tables, and times are seconds since 2026 in four bytes, which last until 2094.
