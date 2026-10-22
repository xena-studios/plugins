---
title: Privacy
description: What the plugins send, and to whom.
sidebar:
  order: 6
---

**zChat 2.0.0 and xLimbo 1.0.1 send nothing.** They make no network connections of their own. (xLimbo's `/join` asks your proxy to move a player, which never leaves your network.)

## The update checker

xUtilities 2 asks [Modrinth](https://modrinth.com) for its newest version at startup and every 24 hours. It's on by default; turn it off with `update-checker.enabled: false` in its config, and it makes no connections at all.

The whole request, from xUtilities 2.0.0 on Paper 1.21.11:

```
GET https://api.modrinth.com/v2/project/xutilities/version?loaders=["paper"]&game_versions=["1.21.11"]&include_changelog=false
User-Agent: xena-studios/xutilities/2.0.0 (contact@xenastudios.co)
```

That's the plugin and its version, the platform (`paper`, or `folia` on Folia) and the Minecraft version. Like any web request, it comes from your server's IP address; Modrinth's [privacy policy](https://modrinth.com/legal/privacy) covers what Modrinth does with it. Nothing about your players is sent. zChat and xLimbo get the same checker in their next major versions.

## Coming: bStats

The next major versions will add **[bStats](https://bstats.org)**, anonymous usage statistics shared by thousands of plugins, on by default: a random server ID, player count, server software and version, Minecraft, Java and plugin versions, operating system, CPU cores, online mode, and country (worked out by bStats from the IP address, which bStats doesn't store). Turn it off for every plugin at once in `plugins/bStats/config.yml`. xUtilities 2.0.0 doesn't include it yet; this page will say when a version does.
