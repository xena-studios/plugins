---
title: xBackup
description: "xBackup: incremental backups for Paper and Folia servers that store only what changed, chunk by chunk, copy it to S3, SFTP, WebDAV or a cloud drive, and check every restore."
sidebar:
  label: Overview
  order: 1
---

xBackup backs up Paper and Folia servers incrementally, storing only what changed since the last backup, down to single chunks. Hourly backups take almost no disk, and the server pauses for milliseconds, not seconds.

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **Only what changed.** A world's region files are kept chunk by chunk: edit one chunk and a backup stores one chunk, not an 8 MB region file. Everything else is stored once however many backups use it.
- **A restore you can trust.** Every file comes back checked against the hash it was saved with. You always see what a restore will change before it happens, and it never writes over a running world: it's put in place on the next start, before any world loads, or from the command line with the server stopped.
- **Databases copied safely.** SQLite databases (xRewind's, xPlots', any plugin's) are copied by SQLite itself, never as raw bytes, so a backup never holds a half-written database.
- **Copies off the server.** A second disk, S3 (AWS, Cloudflare R2, Backblaze B2, Wasabi, MinIO and the rest), WebDAV (Nextcloud and others), SFTP, FTP, Google Drive, OneDrive and Dropbox. Only new files are sent.

## How it works

Backups go into a **repository**, a folder (`plugins/xBackup/repository` unless you move it). Inside, every piece of data is stored once, named by its SHA-256, in files that are written once and never changed. Each backup is one small file listing what it holds, written last, so a crash or a full disk never leaves a broken backup behind.

Each backup reads the worlds twice: first while the server saves as usual, then, with autosave off and the worlds just written to disk, only what changed since the first read. So autosave is off for as little time as possible, and the backup is one exact moment of every world.

Old backups are thinned out by [retention](/xbackup/configuration/#retention): by default the newest three, one an hour for a day, one a day for a week, one a week for a month and one a month for six months. Space they used alone is given back once a day.

## What else it does

- **You won't feel it running.** Autosave goes off, the worlds are written to disk, xBackup reads what changed, and autosave goes back on. The server's own thread spends 15 to 45 ms on it in tests on Paper (15 to 60 ms per region on Folia); `/backup status` shows the real number. See [Performance](/xbackup/performance/).
- **Every change applies on reload.** No restarts.
- **Paper and Folia.** The same jar runs on both.

## Getting started

1. [Install it](/start/installing/). It works out of the box: a backup every hour, kept in `plugins/xBackup/repository`.
2. Run `/backup now`, then `/backup status`.
3. Add a copy somewhere else: [Remotes](/xbackup/remotes/).
4. Know how to get things back before you need to: [Restoring](/xbackup/restoring/).
5. Every command and permission: [Commands](/xbackup/commands/).
