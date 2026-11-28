# xBackup changelog

## 2.0.0 (unreleased)

Not released yet: 2.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 1.0.1 is the release, and 1.x gets its fixes.

The rewrite: the same commands, permissions, settings, remotes and repository as 1.0.1, now on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3** and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), with xBackup's messages in eight languages. The repository format is 1.x's byte for byte: 2.0 reads every backup 1.0.1 made, and 1.0.1 reads 2.0's. Keep the 1.0.1 jar to go back to.

### New

- **The Xena plugins' databases are copied by the plugins themselves.** xMoney, xBans and the rest copy their SQLite database for each backup, from their own database writer, between two of their writes: nothing is ever half in a backup, and nothing waits on xBackup. A database on MySQL or MariaDB is named in each backup and in `/backup status` as not in the backups, so nobody mistakes a backup for a copy of it. See [Databases](https://plugins.xenastudios.co/xbackup/databases/).
- **Spigot, CraftBukkit and older Paper**, from 1.8.8: the worlds are saved with `World#save()` and xBackup waits, off the server's thread, until the region files stop changing.
- **Fabric**, server-side, in `config/xbackup/`: saving off and the worlds flushed as `/save-off` and `/save-all flush` do.
- **Messages in `lang/`**, in English, German, Spanish, French, Brazilian Portuguese, Russian, Polish and Simplified Chinese, each player in their own.
- **`/xbackup debug`** writes a report to share when asking for help.
- **The command-line restore runs on Java 8** and newer, and restores a Fabric server's backups too.

### Upgrading from 1.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v1`; the repository and the remotes' copies are used as they are. See [Upgrading from 1.x](https://plugins.xenastudios.co/xbackup/upgrade/).

- **Messages are in `lang/` now**, one file per language, no longer in `config.yml`. Every message you changed in 1.x is copied into `lang/en.yml` on the first start, and then xBackup sends English to everyone (`messages.language: en`), as 1.x did.
- **The broadcasts** are switched by `broadcasts.start` and `broadcasts.done`; one you'd given text in 1.x is switched on.
- **`theme` moved into `messages.theme`**, shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here. Colours you changed in 1.x stay xBackup's own.
- `update-checker` is `updates` now: `update-checker.enabled` is carried to `updates.check`.
- **Going back to 1.0.1:** stop the server, put the 1.0.1 jar back, and rename `config.yml.bak-v1` to `config.yml` (keep 2.0's as `config.yml.v2`). The repository needs nothing.

### For developers: the API's breaking changes

- `XBackupApi.get()` is `XBackup.api()`; on Bukkit the API is also in the services manager.
- Plugins are named by name rather than passed as a `Plugin`, paths are strings relative to the server folder, and times are milliseconds, so the API is the same on every platform.
- The events are xBackup's own (`XBackup.api().getEvents()`), with Bukkit events beside them in `co.xenastudios.xbackup.api.bukkit`.

## 1.0.1

### Fixed

- While Modrinth is still reviewing xBackup, the update checker asks GitHub for its releases instead, so you hear about new versions either way. It sends GitHub only xBackup's version, as it does Modrinth.

## 1.0.0

The first release. Drop the jar in and start the server: it backs up every hour into `plugins/xBackup/repository`, with nothing to set up. Run `/backup now`, then `/backup status`.

### Backups

- Incremental: only what changed since the last backup is stored. Region files are kept chunk by chunk, so editing one chunk stores one chunk; everything else is stored once however many backups use it, and big files are cut at content-defined points.
- A short, exact pause: autosave off, players saved, worlds written to disk (per region on Folia), the changes read, autosave back on. The worlds are read once before the pause, so the pause only covers what changed since. `/backup status` shows how long the server spent on it.
- Every loaded world in both the 1.21.11 and the 26.x folder layouts, the plugins folder and the server's own settings files. Add more with `sources.extra`, leave things out with `exclude`. A world kept on another disk behind a link is backed up as the folder it is.
- Runs on an empty server too: Paper pauses one with nobody online, and backups carry on without waking it.
- Autosave always goes back on, for exactly the worlds a backup turned off, however it ends.
- SQLite databases are copied by SQLite itself, never as raw bytes, so a plugin writing to one meanwhile is fine.
- Each backup reads back what it wrote before it counts, and a share of older data is checked every week. Data a check finds damaged is stored again by the next backup, from the live files, instead of being reused.
- Retention by tiers (the newest few, then hourly, daily, weekly and monthly), pinned backups kept forever, an optional size cap, and free space always left on the disk. A backup that couldn't finish doesn't start.
- Skips scheduled backups when nobody has been online since the last one.

### Restores

- `/backup restore <id> [path...]` shows what would change, then stages the files, each checked against its hash, and swaps them in on the next start, before any world loads. `/backup restore cancel` calls it off.
- All or nothing: if putting a restore in place fails part way, everything is moved back as it was, including what a start cut off by a crash had already put in place. If even that fails, xBackup stops the server from starting rather than let it load half a world.
- From the command line with the server stopped: `java -jar xbackup-1.0.0.jar restore latest`, with `--dry-run`, `--to <folder>` (new or empty) and `--remote <name>`. It refuses while the server is running, wherever its worlds are, and holds `running.lock` until it's done, so a server started meanwhile stops before loading any world.
- A restore only ever writes inside its folder, and doesn't restore links that point outside the server folder, directly or through a link already there, unless you ask (`--allow-links-outside`). On Windows, names that Windows would read as another file are refused.
- Whatever a restore replaces is kept in `plugins/xBackup/restore/replaced/`.

### Remotes

- Copies of the repository to a second disk, S3 and S3-compatible storage (AWS, Cloudflare R2, Backblaze B2, Wasabi, MinIO and others), WebDAV, SFTP, FTP/FTPS, Google Drive, OneDrive and Dropbox, after every backup. Only new files are sent, and an interrupted copy resumes.
- Restores straight from a remote, downloading only what the restore needs.
- A copy only removes from a remote what retention, `/backup forget` or a clean-up removed here. Data that went missing here stays on the remote for good, and a damaged, emptied or rolled-back repository never deletes the remote's backups. `/backup copy <remote> tidy` lists what only the remote has, and removes it once you confirm, which needs `xbackup.forget` too.
- Passwords and secret keys go in `plugins/xBackup/secrets.yml`, never in `config.yml`. Only the server's user can read it, and it's never backed up or copied to a remote; nor are SFTP keys and cloud drive tokens. A password left in `config.yml` (from a pre-release build, say) is moved there when the config loads. The command-line restore reads `secrets.yml` itself, so no other file holds the passwords.
- FTPS checks the server's certificate and host name, or a pinned fingerprint (`host-key`) for a self-signed one, and every data connection against the control connection's certificate and address.
- Stalled transfers time out, uploads included, and uploads are checked under a temporary name before they take the real one.
- `/backup link` links the cloud drives; tokens are kept in an owner-only `tokens.yml`, never in `config.yml`.

### Commands

- `/backup now`, `list`, `status`, `verify`, `restore`, `prune`, `pin`, `unpin`, `forget`, `copy`, `link` and `repair`, each with its own permission.

### For developers

- A developer API, `co.xenastudios.xbackup:xbackup-api`: run a backup, read the list and status from memory, add folders to back up, keep a plugin's files still while they're read, add a storage type, and `BackupStartEvent` (cancellable), `BackupFinishedEvent` and `BackupFailedEvent`.
