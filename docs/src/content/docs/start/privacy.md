---
title: Privacy
description: What the plugins send, and to whom.
sidebar:
  order: 6
---

Every connection the plugins make, what's sent, and how to turn it off.

## The update checker

Every plugin asks [Modrinth](https://modrinth.com) for its newest version at startup and every 24 hours. It's on by default; turn it off with `update-checker.enabled: false` in its config, and it makes no connections at all.

The whole request, from xUtilities 2.0.0 on Paper 1.21.11:

```
GET https://api.modrinth.com/v2/project/xutilities/version?loaders=["paper"]&game_versions=["1.21.11"]&include_changelog=false
User-Agent: xena-studios/xutilities/2.0.0 (contact@xenastudios.co)
```

That's the plugin and its version, the platform (`paper`, or `folia` on Folia) and the Minecraft version. Like any web request, it comes from your server's IP address; Modrinth's [privacy policy](https://modrinth.com/legal/privacy) covers what Modrinth does with it. Nothing about your players is sent. The other plugins send the same, with their own name in place of `xutilities`.

While Modrinth is still reviewing a plugin, Modrinth answers that it doesn't know it, so the plugin asks GitHub for its releases instead (xRewind 1.1.1, xPlots 1.0.2, xTeleport, xVote, xBans, xMoney, xStats and xBackup 1.0.1, and later):

```
GET https://api.github.com/repos/xena-studios/plugins/releases?per_page=100
User-Agent: xena-studios/xbans/1.0.1 (contact@xenastudios.co)
```

That's only the plugin and its version. GitHub's [privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement) covers what GitHub does with it.

## bStats

xUtilities 2, zChat 3, xLimbo 2, xRewind, xPlots, xTeleport, xVote, xBans, xMoney, xBackup and xStats send **[bStats](https://bstats.org)**, anonymous usage statistics shared by thousands of plugins, on by default: a random server ID, player count, server software and version, Minecraft, Java and plugin versions, operating system, CPU cores, online mode, and country (worked out by bStats from the IP address, which bStats doesn't store). Turn it off for every plugin at once in `plugins/bStats/config.yml`. (zChat 1.x sent bStats too; zChat 2 and xLimbo 1 didn't.) The numbers are public: [xUtilities](https://bstats.org/plugin/bukkit/XenaUtilities/34866), [zChat](https://bstats.org/plugin/bukkit/zChat/21533), [xLimbo](https://bstats.org/plugin/bukkit/xLimbo/34867), [xRewind](https://bstats.org/plugin/bukkit/xRewind/35410), [xPlots](https://bstats.org/plugin/bukkit/xPlots/35419), [xTeleport](https://bstats.org/plugin/bukkit/XenaTeleport/35423), [xVote](https://bstats.org/plugin/bukkit/xVote/35422), [xBans](https://bstats.org/plugin/bukkit/xBans/35418), [xMoney](https://bstats.org/plugin/bukkit/xMoney/35421), [xBackup](https://bstats.org/plugin/bukkit/xBackup/35420) and [xStats](https://bstats.org/plugin/bukkit/xStats/35436) on bStats.

xRewind keeps what it logs (who changed which blocks, and when, where they joined and quit) in your own database, and nothing of it leaves your server. Chat and command logging are off unless you turn them on, and history older than 30 days is deleted unless you keep it longer (`purge.keep-days`).

xBans keeps punishments, players' names and the IP addresses they joined from in your own database, and nothing of it leaves your server. IPs can be stored as salted hashes, or not at all: see [xBans' privacy page](/xbans/privacy/).

## xStats' website

xStats' website is off until you turn it on. Once it is, anyone who can reach its port sees what you chose to show: the stats with `website: true`, players' names, when they first joined and were last seen, and their places on leaderboards. Players who run `/stats private` are left off it entirely. xStats never stores an IP address: the rate limit counts each visitor's requests in memory for a minute, and nothing about visitors is written anywhere.

Players' heads on the site come from [mc-heads.net](https://mc-heads.net) (or the service you set in `web.avatars.url`), loaded by each visitor's browser, so that service sees the visitor's IP address and which player's head they're looking at. Set `web.avatars.proxy: true` and xStats fetches heads itself instead, from your server, and keeps them for a day; then visitors' browsers only ever talk to your server.

## xVote's receiver

xVote listens for votes from server lists, on port 8192 unless you change it, until you turn it off with `receiver.enabled: false`. Each vote carries the player's name, the list's name, the time and the voter's IP address as the list reports it. xVote keeps votes in your own database without the address, which it passes only to plugins listening for votes (1.0.0 kept it; 1.0.1 clears it).

## xBackup's remotes

xBackup sends your backups only to the remotes you add (S3, WebDAV, SFTP, FTP, Google Drive, OneDrive or Dropbox); with none, it sends them nowhere. `/backup link` signs in to Google Drive, OneDrive or Dropbox with the app you register there. The first time an SFTP or FTP remote is used, xBackup downloads the library it needs from Maven Central (Google's mirror of it first): see [Remotes](/xbackup/remotes/#libraries-for-sftp-and-ftp).

## Inside your network

xLimbo's `/join` and automatic returns ask your proxy to move a player, which never leaves your network. With [server-check](/xlimbo/network-setup/#send-everyone-back-when-its-up) on, xLimbo also asks your main server for its status, at the address you give it.
