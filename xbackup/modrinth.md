![xBackup by Xena Studios. An ice blue pixel safe icon and the xBackup name over a stepped horizon in ice blue, with the headline "Backups that cost nothing. Restores you can trust."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xbackup/banner.png)

## Backups that cost nothing. Restores you can trust.

Most backup plugins zip your whole server every time: slow, big, and a long freeze while they save. **xBackup stores only what changed since the last backup, down to single chunks.** Hourly backups take almost no disk, and the server pauses for milliseconds.

**[Read the docs](https://docs.xenastudios.co/xbackup/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Only what changed.** Edit one chunk and the next backup stores one chunk, not an 8 MB region file. Everything is stored once, however many backups use it, so a day of hourly backups costs about what changed that day, not 24 copies of the server.
- **A pause you can measure.** Autosave goes off, the worlds are written to disk, xBackup reads what changed, and autosave goes back on: tens of milliseconds on the server's thread. `/backup status` shows the real number for every backup.
- **A restore you can trust.** Every file comes back checked against the hash it was saved with. You see what a restore will change before it happens, and it never writes over a running world: it's swapped in on the next start, before any world loads.
- **Databases copied safely.** SQLite databases (block loggers, economies, any plugin's) are copied by SQLite itself, never as raw bytes, so a backup never holds a half-written database.
- **Every common remote.** A second disk, S3 (AWS, Cloudflare R2, Backblaze B2, Wasabi, MinIO), WebDAV (Nextcloud), SFTP, FTP/FTPS, Google Drive, OneDrive and Dropbox. Only new files are sent, and you can restore straight from a remote.
- **Paper and Folia.** One jar for both.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

---

## What you get

- **Backups on a schedule**, every hour by default, counted from the last one so restarts don't reset it, and skipped when nobody has been online.
- **Retention that makes sense:** the newest few, then one an hour for a day, a day for a week, a week for a month and a month for six months. Pin a backup to keep it forever, cap the space it may use, and always leave free space on the disk.
- **Restores in game:** `/backup restore latest world` shows what would change; one click stages it for the next start. Or restore one plugin's folder, or one file.
- **Restores from the command line** when the server won't start: `java -jar xbackup-1.0.0.jar restore latest`, or `--remote offsite` when the disk is gone.
- **Checked, always:** each backup reads back what it wrote, a share of older data is checked every week, and `/backup verify full` checks everything.
- **Built to survive a crash.** Nothing in a repository is ever changed in place, so a crash, a power cut or a full disk mid-backup leaves every earlier backup whole.
- **A developer API** for other plugins: back up before an event, add a folder, cancel a backup during a fight.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. It backs up every hour into `plugins/xBackup/repository`.
2. Run `/backup now`, then `/backup status`.
3. Add a remote in `plugins/xBackup/config.yml`, so a copy lives somewhere else, and `/xbackup reload`.

Every setting, every remote and how to restore: **[docs.xenastudios.co/xbackup](https://docs.xenastudios.co/xbackup/)**

**Requires** Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. Nothing else.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute. Select an area and every plot and sign is made for you.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xbackup)
