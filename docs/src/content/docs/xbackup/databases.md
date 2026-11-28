---
title: Databases
description: "How xBackup copies databases: the Xena plugins' through their own writers, any other SQLite database through SQLite, and what it does with MySQL."
sidebar:
  order: 3.5
---

A database copied as raw bytes while it's being written can come back corrupt, with a write-ahead log that doesn't match it, or missing what was still in the log. xBackup never does that. Every database in a backup is a copy made by a database engine, in one consistent moment.

## The Xena plugins' databases

xMoney, xBans and every other Xena plugin on the same server tell xBackup where their SQLite databases are. Each backup then asks each plugin for a copy, and the plugin's own database writer makes it between two of its writes, on its own thread, so no payment or ban is ever half in a backup and nothing waits on xBackup. It uses SQLite's `VACUUM INTO`, or the online backup API, or, on an SQLite too old for either, a copy taken with the database locked for writing, its log folded in. `/backup status` lists the plugins whose databases it copied this way.

This works on Bukkit and on Fabric, with the plugins started in any order. A plugin's database shared by several Xena plugins, such as `plugins/Xena/xena.db`, is copied once. Nothing needs setting up.

## Any other SQLite database

A plugin outside the family's database (`.db`, `.sqlite`, `.sqlite3`) is copied by SQLite itself, through the server's own SQLite driver (Paper and Spigot ship one). On Fabric, which has none, the driver is downloaded the first time a backup needs it. Its `-wal`, `-shm` and `-journal` files aren't needed and aren't copied. A database that can't be copied that way is left out of that backup, with a warning in the console and in `/backup list`, rather than stored broken.

## MySQL and MariaDB

These live on a database server, outside the server folder, and xBackup doesn't back them up. When a Xena plugin uses one, each backup records which (`xMoney: MySQL at db.example.com:3306/mc (xmoney_ tables)`), and `/backup status` shows it under "Not in the backups", so nobody mistakes a backup for a copy of it. Back them up with the database's own tools, such as `mysqldump` on a schedule.

## Restoring a database

A restored SQLite database's old `-wal` and `-shm` files are set aside with it, so SQLite never replays them over the restored copy. Restore a plugin's database with the server stopped or staged for the next start, as every restore is: see [Restoring](/xbackup/restoring/).
