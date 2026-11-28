---
title: Remotes
description: Copying xBackup's backups to a second disk, S3, WebDAV, SFTP, FTP, Google Drive, OneDrive or Dropbox.
sidebar:
  order: 3
---

A backup on the same disk as the server doesn't survive that disk. A **remote** keeps a copy of the whole repository somewhere else, updated after every backup. Files in a repository never change, so only new ones are sent: after the first copy, an hourly copy is usually a few small files. An interrupted copy picks up where it stopped.

Add remotes under `remotes` in `config.yml`, each with a name you choose, put their passwords in `secrets.yml`, and `/xbackup reload`. `/backup status` shows how each one's last copy went, and `/backup copy <name>` copies now.

```yaml
# config.yml
remotes:
  offsite:
    type: "s3"
    url: "https://s3.eu-central-1.amazonaws.com"
    region: "eu-central-1"
    bucket: "my-backups"
    username: "AKIA..."       # the access key id
    path: "survival"          # a folder in the bucket, one per server
```

```yaml
# secrets.yml, beside config.yml
offsite:
  password: "..."             # the secret key
```

Settings a type doesn't use are ignored. Give each server its own `path`: two servers copying into one folder would remove each other's backups. xBackup refuses to copy onto a folder that holds a different repository, so a server whose own repository was lost can't wipe the remote copy it needs; [restore from the remote](/xbackup/v1/restoring/#from-a-remote) instead.

## Passwords

Passwords, secret keys and Google Drive's client secret go in `plugins/xBackup/secrets.yml`, by remote name, never in `config.yml`. That way you can share your `config.yml` when asking for help, and it can be backed up and copied with everything else. `secrets.yml` is readable by the server's user only, and is never backed up or copied to a remote. If you put a `password` or `client-secret` in `config.yml` anyway, xBackup moves it to `secrets.yml` the next time the config loads.

Nor are SFTP private key files, cloud drive tokens (`tokens.yml`) and `restore-settings.properties` ever backed up. All of them are readable by the server's user only; on Windows, xBackup sets their access list to the server's user alone. `restore-settings.properties` holds no passwords: the command-line restore reads them from `secrets.yml` itself, so write each value there on one line (in quotes if it has spaces or symbols).

If a password was in `config.yml` before xBackup moved it, backups taken until then hold `config.yml` with the password in it, and so do the remotes' copies of them. Change that password if anyone else could read those backups.

## What a copy removes

Backups that retention or `/backup forget` removed are removed from the remote too, and so is the data a clean-up gave back here. Nothing else is: the remote is the copy you need when the server's own repository is damaged, rolled back with its disk, or restored from an old copy. A pack of data that went missing here (a bad disk, a careless delete) stays on the remote for good, with the remote's index files, since older backups may need it. And a copy removes nothing from the remote when:

- the server's repository has no backups, or can't read one;
- its index lists data that's missing from its disk;
- the remote has backups the server's repository doesn't, and didn't remove on purpose. Those backups, and the data they use, stay on the remote.

The console says when it held back, and why. [Restore from the remote](/xbackup/v1/restoring/#from-a-remote) if the server's repository lost something.

When you know you won't need what only the remote has (the disk was rolled back on purpose, say), `/backup copy <name> tidy` lists the backups the remote has that this server doesn't, and how much removing them frees. `/backup copy <name> tidy confirm` copies first, then removes them, and every pack on the remote that no backup here needs. Packs holding data that backups here need, and this server lost, are kept.


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
| `username` | The access key id; the secret key goes in `secrets.yml` as `password`. The key needs to list, read, write and delete in the bucket. |
| `path-style` | `true` for MinIO and others that want `endpoint/bucket/key` addresses. |
| `path` | A folder in the bucket. |

Every upload carries its checksum, so the service refuses one that arrives damaged.

## WebDAV

Nextcloud, ownCloud, Synology, Koofr, or any WebDAV server.

| Setting | |
| --- | --- |
| `url` | The folder's WebDAV address. Nextcloud: `https://cloud.example.com/remote.php/dav/files/<user>/`. |
| `username` | The login. Its password goes in `secrets.yml`: for Nextcloud and ownCloud, an app password. |
| `path` | A folder inside `url`, made if it isn't there. |

## SFTP

| Setting | |
| --- | --- |
| `host`, `port` | The server, and its port (22 if 0). |
| `username` | The login. Its password (or the private key's passphrase) goes in `secrets.yml`. |
| `private-key` | A key file in `plugins/xBackup/`, instead of a password. It's never backed up. |
| `host-key` | The server's key fingerprint, `SHA256:...` (`ssh-keygen -lf` shows it). Blank trusts the key seen on the first connection and checks every later one against it (kept in `known-hosts.properties`). |
| `path` | The folder, relative to the login's home. |

## FTP and FTPS

| Setting | |
| --- | --- |
| `host`, `port` | The server, and its port (21, or 990 for implicit FTPS, if 0). |
| `username` | The login. Its password goes in `secrets.yml`. |
| `security` | `explicit` (FTPS through `AUTH TLS`, the default), `implicit` (TLS from the start) or `none` (plain FTP, only on a network you trust). |
| `host-key` | For a server with a self-signed certificate: its SHA-256 fingerprint, as `openssl x509 -noout -fingerprint -sha256 -in cert.pem` prints it, or as xBackup's error shows it. Blank trusts only certificates Java trusts, issued for `host`. |
| `path` | The folder. |

With TLS, xBackup checks the server's certificate: one Java trusts, for the host name in `host`, or the one whose fingerprint is in `host-key`. A server it can't check is refused, and the error gives the certificate's fingerprint.

Every data connection must show the same certificate as the control connection, and goes to the address the control connection reached, never to one the server names. Some FTPS servers insist the data connection reuses the control connection's TLS session (vsftpd's `require_ssl_reuse=YES`, its default), which the FTP library xBackup uses can't do: uploads fail with an error about the TLS session. Set `require_ssl_reuse=NO` in `vsftpd.conf`, or use SFTP. An upload the server stops taking fails after a minute and is sent again on the next copy.

## Libraries for SFTP and FTP

SFTP and FTP need a library each ([JSch](https://github.com/mwiede/jsch) and [Apache Commons Net](https://commons.apache.org/proper/commons-net/)), downloaded from Maven Central (Google's mirror of it first) the first time a remote of that type is used, checked against a fixed SHA-256, and kept in `plugins/xBackup/libraries/`. If your host blocks downloads, `/backup status` says which file to put there by hand.

## Cloud drives

Google Drive, OneDrive and Dropbox are linked from the game: `/backup link <name>`. Each needs an app registered with the provider, and its client id in the remote's settings. xBackup only ever sees its own folder: Drive's files it made itself, OneDrive's and Dropbox's app folder.

```yaml
  drive:
    type: "drive"
    client-id: "1234-abc.apps.googleusercontent.com"
    path: "xbackup"
```

```yaml
# secrets.yml
drive:
  client-secret: "GOCSPX-..."
```

**Google Drive.** In the [Google Cloud console](https://console.cloud.google.com/): make a project, enable the Google Drive API, set up the OAuth consent screen (add yourself as a test user, or publish it), and create an OAuth client of type **TVs and Limited Input devices**. Its client id goes in `client-id`, and its secret in `secrets.yml` as `client-secret`. Then `/backup link drive` shows a code: enter it at the address it gives, on any device.

**OneDrive.** In the [Azure portal](https://portal.azure.com/), **App registrations** → New registration, for personal Microsoft accounts (and work accounts if you like). Under **Authentication**, turn on **Allow public client flows**. Under **API permissions**, add Microsoft Graph's delegated `Files.ReadWrite.AppFolder` and `offline_access`. Its application (client) id goes in `client-id`; there's no secret. `/backup link <name>` shows a code to enter at microsoft.com/devicelogin.

**Dropbox.** In the [Dropbox app console](https://www.dropbox.com/developers/apps), create an app with **App folder** access, and under **Permissions** tick `files.content.write` and `files.content.read`. Its app key goes in `client-id`. `/backup link <name>` gives a link: open it, allow the app, then run `/backup link <name> <code>` with the code Dropbox shows.

Unlink a drive by deleting its lines from `tokens.yml`, or revoke xBackup's access in the provider's account settings.

## From another plugin

A plugin can add its own kind of remote through xBackup's [developer API](/xbackup/v1/developer-api/); a remote with `type:` set to its name then uses it.
