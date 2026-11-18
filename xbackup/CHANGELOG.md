# xBackup changelog

## 1.0.0

The first release. Drop the jar in and start the server: it backs up every hour into `plugins/xBackup/repository`, with nothing to set up. Run `/backup now`, then `/backup status`.

### Backups

- Incremental: only what changed since the last backup is stored. Region files are kept chunk by chunk, so editing one chunk stores one chunk; everything else is stored once however many backups use it, and big files are cut at content-defined points.
- A short, exact pause: autosave off, players saved, worlds written to disk (per region on Folia), the changes read, autosave back on. The worlds are read once before the pause, so the pause only covers what changed since. `/backup status` shows how long the server spent on it.
- Every loaded world in both the 1.21.11 and the 26.x folder layouts, the plugins folder and the server's own settings files. Add more with `sources.extra`, leave things out with `exclude`. A world kept on another disk behind a link is backed up as the folder it is.
- Runs on an empty server too: Paper pauses one with nobody online, and backups carry on without waking it.
- Autosave always goes back on, for exactly the worlds a backup turned off, however it ends.
- SQLite databases are copied by SQLite itself, never as raw bytes, so a plugin writing to one meanwhile is fine.
- Each backup reads back what it wrote before it counts, and a share of older data is checked every week.
- Retention by tiers (the newest few, then hourly, daily, weekly and monthly), pinned backups kept forever, an optional size cap, and free space always left on the disk. A backup that couldn't finish doesn't start.
- Skips scheduled backups when nobody has been online since the last one.

### Restores

- `/backup restore <id> [path...]` shows what would change, then stages the files, each checked against its hash, and swaps them in on the next start, before any world loads. `/backup restore cancel` calls it off.
- All or nothing: if putting a restore in place fails part way, everything is moved back as it was. If even that fails, xBackup stops the server from starting rather than let it load half a world.
- From the command line with the server stopped: `java -jar xbackup-1.0.0.jar restore latest`, with `--dry-run`, `--to <folder>` (new or empty) and `--remote <name>`. It refuses while the server is running, wherever its worlds are.
- A restore only ever writes inside its folder, and doesn't restore links that point outside the server folder unless you ask (`--allow-links-outside`).
- Whatever a restore replaces is kept in `plugins/xBackup/restore/replaced/`.

### Remotes

- Copies of the repository to a second disk, S3 and S3-compatible storage (AWS, Cloudflare R2, Backblaze B2, Wasabi, MinIO and others), WebDAV, SFTP, FTP/FTPS, Google Drive, OneDrive and Dropbox, after every backup. Only new files are sent, and an interrupted copy resumes.
- Restores straight from a remote, downloading only what the restore needs.
- A copy only removes from a remote what retention or `/backup forget` removed here. A damaged, emptied or rolled-back repository never deletes the remote's backups.
- Passwords and secret keys go in `plugins/xBackup/secrets.yml`, never in `config.yml`. Only the server's user can read it, and it's never backed up or copied to a remote; nor are SFTP keys and cloud drive tokens. A password left in `config.yml` (from a pre-release build, say) is moved there when the config loads.
- FTPS checks the server's certificate and host name, or a pinned fingerprint (`host-key`) for a self-signed one.
- Stalled transfers time out, and uploads are checked under a temporary name before they take the real one.
- `/backup link` links the cloud drives; tokens are kept outside `config.yml`.

### Commands

- `/backup now`, `list`, `status`, `verify`, `restore`, `prune`, `pin`, `unpin`, `forget`, `copy`, `link` and `repair`, each with its own permission.

### For developers

- A developer API, `co.xenastudios:xbackup-api`: run a backup, read the list and status from memory, add folders to back up, keep a plugin's files still while they're read, add a storage type, and `BackupStartEvent` (cancellable), `BackupFinishedEvent` and `BackupFailedEvent`.
