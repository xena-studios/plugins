![xBackup by Xena Studios. An apricot pixel safe icon and the xBackup name over a stepped horizon in apricot, with the headline "Backups that cost nothing. Restores you can trust."](../.github/assets/xbackup-banner.png)

**Backups that cost nothing. Restores you can trust.**

xBackup backs up a Paper or Folia server incrementally: only what changed since the last backup is stored, down to single chunks, so hourly backups take almost no disk and the server pauses for milliseconds.

- **Only what changed.** Region files are kept chunk by chunk, and everything is stored once, named by its SHA-256, however many backups use it.
- **A pause you can measure.** Autosave off, worlds flushed with `World#save(true)` (per region on Folia), the changes read, autosave back on. About 20 to 80 ms on the server's thread.
- **A restore you can trust.** Every file comes back checked against its hash. A dry run first, then it's swapped in on the next start before any world loads, or from the command line with the server stopped.
- **Databases copied safely,** by SQLite itself (`VACUUM INTO`), never as raw bytes.
- **Copies off the server:** a second disk, S3-compatible storage, WebDAV, SFTP, FTP/FTPS, Google Drive, OneDrive and Dropbox. Only new files are sent.

Needs Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. Nothing else.

**[Download on Modrinth](https://modrinth.com/plugin/xbackup)** · [Docs](https://docs.xenastudios.co/xbackup/) · [Restoring](https://docs.xenastudios.co/xbackup/restoring/) · [Remotes](https://docs.xenastudios.co/xbackup/remotes/) · [Changelog](CHANGELOG.md)

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :xbackup:build       # compile, test, and check the jar's size budget
./gradlew :xbackup:runServer   # a Paper server with xBackup, in xbackup/run/
```

```
src/main/java/co/xenastudios/xbackup/
  XBackup.java         the plugin: the engine, /backup, the API and the idle listener
  XBackupBootstrap.java  puts a staged restore in place on start, before worlds load
  store/               the repository: packs, index, trees, region layouts, snapshots, verify, GC (pure Java)
  backup/              what a backup reads: the two passes, region files chunk by chunk, SQLite copies
  restore/             finding, dry-running and writing a restore, and the journaled swap
  remote/              S3 (SigV4), WebDAV, the cloud drives and OAuth, copying and fetching
  remote/lib/          SFTP and FTP, loaded with libraries fetched at runtime
  server/              the worker thread, the schedule, the autosave pause on Paper and Folia
  command/             /backup
  cli/                 java -jar xbackup.jar list|restore|verify
api/                   the developer API (co.xenastudios:xbackup-api)
boot-checks.txt        console commands CI runs on real Paper and Folia servers
```

The store engine, the backup scanner and restores are pure Java with no server classes, so they're tested without a server: `StoreTest`, `BackupTest` (real-format region files, restores compared byte for byte), `CrashTest` (every operation killed at random writes, 160 rounds) and `KillTest` (a second JVM killed with SIGKILL mid-backup).

`RemoteTest` runs every network remote against real servers in Docker when `XB_REMOTES` is set:

```sh
docker run -d -p 19000:9000 -e MINIO_ROOT_USER=xbackup -e MINIO_ROOT_PASSWORD=xbackupsecret cgr.dev/chainguard/minio server /data
docker run -d -p 18080:80 -e AUTH_TYPE=Basic -e USERNAME=xbackup -e PASSWORD=pass bytemark/webdav
docker run -d -p 12222:22 atmoz/sftp xbackup:pass:::upload
docker run -d -p 12121:21 -p 21000-21010:21000-21010 -e "USERS=xbackup|pass" -e ADDRESS=127.0.0.1 \
  -e MIN_PORT=21000 -e MAX_PORT=21010 delfer/alpine-ftp-server
XB_REMOTES=1 ./gradlew :xbackup:test
```

The cloud drives are tested against local stand-ins for their APIs (`CloudDrivesTest`).

The shared core (reloading, config migration, the theme, the update checker, bStats) is in [`core/`](../core).

## Licence

[GPL-3.0-only](../LICENSE), like the rest of the repo.
