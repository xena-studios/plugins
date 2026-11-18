---
title: Developer API
description: Running backups, reading their status, and adding sources and storage from another plugin.
sidebar:
  order: 7
---

xBackup has a public API in `co.xenastudios.xbackup.api`, shipped inside its jar. Depend on xBackup (`depend` or `softdepend` in `plugin.yml`, or a server dependency in `paper-plugin.yml`), compile against `co.xenastudios.xbackup:xbackup-api`, and get it with `XBackupApi.get()`.

Every method says which thread it may be called from: reads answer from memory, and anything that does work returns a `CompletableFuture` that completes off the game threads. Nothing blocks the caller.

```java
XBackupApi backups = XBackupApi.get();

// Back up before something risky.
backups.backupNow("before the event").thenAccept(snapshot ->
    getLogger().info("Backed up " + snapshot.shortId() + ", the server paused " + snapshot.pause().toMillis() + " ms"));

// The newest backup, from memory.
BackupStatus status = backups.getStatus();

// Back up a folder outside the plugins folder.
backups.registerSource(this, Path.of("custom-data"), List.of("custom-data/cache/**"));

// Keep this plugin's files still while xBackup reads them.
backups.registerQuietMoment(this, new QuietMoment() {
    public void pause() { writer.pauseAndFlush(); }
    public void resume() { writer.resume(); }
});
```

SQLite databases need no quiet moment: xBackup always copies them through SQLite itself.

## Events

| Event | Thread | When |
| --- | --- | --- |
| `BackupStartEvent` | Global (main on Paper) | Before a backup. Cancel it to skip that backup, such as during an event. |
| `BackupFinishedEvent` | Async | A backup is stored: its size, duration and pause. |
| `BackupFailedEvent` | Async | A backup failed; nothing was stored for it. |

Events are only built when something listens.

## Storage

`registerBackend(plugin, type, backend)` adds a place backups can be copied to: implement `StorageBackend` (put, get, list, delete whole files), and a remote with `type: <type>` uses it.
