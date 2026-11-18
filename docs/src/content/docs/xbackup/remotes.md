---
title: Remotes
description: Copying xBackup's backups to a second disk, S3, WebDAV, SFTP, FTP, Google Drive, OneDrive or Dropbox.
sidebar:
  order: 3
---

A backup on the same disk as the server doesn't survive that disk. A **remote** keeps a copy of the whole repository somewhere else, updated after every backup. Files in a repository never change, so only new ones are sent: after the first copy, an hourly copy is usually a few small files. An interrupted copy picks up where it stopped. Backups removed by retention are removed from the remote too.

Add remotes under `remotes` in `config.yml`, each with a name you choose, and `/xbackup reload`. `/backup status` shows how each one's last copy went, and `/backup copy <name>` copies now.

```yaml
remotes:
  offsite:
    type: "s3"
    url: "https://s3.eu-central-1.amazonaws.com"
    region: "eu-central-1"
    bucket: "my-backups"
    username: "AKIA..."       # the access key id
    password: "..."           # the secret key
    path: "survival"          # a folder in the bucket, one per server
```

Settings a type doesn't use are ignored. Give each server its own `path`: two servers copying into one folder would remove each other's backups. xBackup refuses to copy onto a folder that holds a different repository, so a server whose own repository was lost can't wipe the remote copy it needs; [restore from the remote](/xbackup/restoring/#from-a-remote) instead.

Passwords and keys stay in `config.yml`, which xBackup makes readable by its owner only once it holds a remote. Cloud drive tokens live in `plugins/xBackup/tokens.properties`, also owner-only.

## Second disk or network mount

```yaml
    type: "local"
    path: "/mnt/backup-disk/xbackup"
```

Any folder: another disk, a NAS mounted over NFS or SMB.

## S3 and compatible

AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO, Hetzner Object Storage, DigitalOcean Spaces and anything else that speaks S3.

| Setting | |
| --- | --- |
| `url` | The endpoint: `https://s3.<region>.amazonaws.com`, `https://<account>.r2.cloudflarestorage.com`, `https://s3.<region>.backblazeb2.com`, your MinIO's address. |
| `region` | The bucket's region. R2 uses `auto`. |
| `bucket` | The bucket, made beforehand. |
| `username`, `password` | The access key id and secret key. The key needs to list, read, write and delete in the bucket. |
| `path-style` | `true` for MinIO and others that want `endpoint/bucket/key` addresses. |
| `path` | A folder in the bucket. |

Every upload carries its checksum, so the service refuses one that arrives damaged.

## WebDAV

Nextcloud, ownCloud, Synology, Koofr, or any WebDAV server.

| Setting | |
| --- | --- |
| `url` | The folder's WebDAV address. Nextcloud: `https://cloud.example.com/remote.php/dav/files/<user>/`. |
| `username`, `password` | For Nextcloud and ownCloud, an app password. |
| `path` | A folder inside `url`, made if it isn't there. |

## SFTP

| Setting | |
| --- | --- |
| `host`, `port` | The server, and its port (22 if 0). |
| `username` | The login. |
| `password` | Its password, or the private key's passphrase. |
| `private-key` | A key file in `plugins/xBackup/`, instead of a password. |
| `host-key` | The server's key fingerprint, `SHA256:...` (`ssh-keygen -lf` shows it). Blank trusts the key seen on the first connection and checks every later one against it (kept in `known-hosts.properties`). |
| `path` | The folder, relative to the login's home. |

## FTP and FTPS

| Setting | |
| --- | --- |
| `host`, `port` | The server, and its port (21, or 990 for implicit FTPS, if 0). |
| `username`, `password` | The login. |
| `security` | `explicit` (FTPS through `AUTH TLS`, the default), `implicit` (TLS from the start) or `none` (plain FTP, only on a network you trust). |
| `path` | The folder. |

Some FTPS servers insist the data connection reuses the control connection's TLS session (vsftpd's `require_ssl_reuse`), which Java can't do; turn that setting off on the server, or use SFTP.

## Libraries for SFTP and FTP

SFTP and FTP need a library each ([JSch](https://github.com/mwiede/jsch) and [Apache Commons Net](https://commons.apache.org/proper/commons-net/)), downloaded from Maven Central the first time a remote of that type is used, checked against a fixed SHA-256, and kept in `plugins/xBackup/libraries/`. If your host blocks downloads, `/backup status` says which file to put there by hand.

## Cloud drives

Google Drive, OneDrive and Dropbox are linked from the game: `/backup link <name>`. Each needs an app registered with the provider, and its client id in the remote's settings. xBackup only ever sees its own folder: Drive's files it made itself, OneDrive's and Dropbox's app folder.

```yaml
  drive:
    type: "drive"
    client-id: "1234-abc.apps.googleusercontent.com"
    client-secret: "GOCSPX-..."
    path: "xbackup"
```

**Google Drive.** In the [Google Cloud console](https://console.cloud.google.com/): make a project, enable the Google Drive API, set up the OAuth consent screen (add yourself as a test user, or publish it), and create an OAuth client of type **TVs and Limited Input devices**. Its client id and secret go in `client-id` and `client-secret`. Then `/backup link drive` shows a code: enter it at the address it gives, on any device.

**OneDrive.** In the [Azure portal](https://portal.azure.com/), **App registrations** → New registration, for personal Microsoft accounts (and work accounts if you like). Under **Authentication**, turn on **Allow public client flows**. Under **API permissions**, add Microsoft Graph's delegated `Files.ReadWrite.AppFolder` and `offline_access`. Its application (client) id goes in `client-id`; there's no secret. `/backup link <name>` shows a code to enter at microsoft.com/devicelogin.

**Dropbox.** In the [Dropbox app console](https://www.dropbox.com/developers/apps), create an app with **App folder** access, and under **Permissions** tick `files.content.write` and `files.content.read`. Its app key goes in `client-id`. `/backup link <name>` gives a link: open it, allow the app, then run `/backup link <name> <code>` with the code Dropbox shows.

Unlink a drive by deleting its lines from `tokens.properties`, or revoke xBackup's access in the provider's account settings.

## From another plugin

A plugin can add its own kind of remote through xBackup's [developer API](/xbackup/developer-api/); a remote with `type:` set to its name then uses it.
