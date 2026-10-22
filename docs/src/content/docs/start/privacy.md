---
title: Privacy
description: What the plugins send, and to whom.
sidebar:
  order: 6
---

**xLimbo 1.0.1 sends nothing.** They make no network connections of their own. (xLimbo's `/join` asks your proxy to move a player, which never leaves your network.)

## The update checker

xUtilities 2 and zChat 3 ask [Modrinth](https://modrinth.com) for its newest version at startup and every 24 hours. It's on by default; turn it off with `update-checker.enabled: false` in its config, and it makes no connections at all.

The whole request, from xUtilities 2.0.0 on Paper 1.21.11:

```
GET https://api.modrinth.com/v2/project/xutilities/version?loaders=["paper"]&game_versions=["1.21.11"]&include_changelog=false
User-Agent: xena-studios/xutilities/2.0.0 (contact@xenastudios.co)
```

That's the plugin and its version, the platform (`paper`, or `folia` on Folia) and the Minecraft version. Like any web request, it comes from your server's IP address; Modrinth's [privacy policy](https://modrinth.com/legal/privacy) covers what Modrinth does with it. Nothing about your players is sent. zChat sends the same, with `zchat` in place of `xutilities`. xLimbo gets the same checker in xLimbo 2.

## bStats

xUtilities 2.0.0 and zChat 3.0.0 and newer send **[bStats](https://bstats.org)**, anonymous usage statistics shared by thousands of plugins, on by default: a random server ID, player count, server software and version, Minecraft, Java and plugin versions, operating system, CPU cores, online mode, and country (worked out by bStats from the IP address, which bStats doesn't store). Turn it off for every plugin at once in `plugins/bStats/config.yml`. xLimbo adds it in xLimbo 2. (zChat 1.x sent bStats too; zChat 2 didn't.) The numbers are public: [xUtilities](https://bstats.org/plugin/bukkit/XenaUtilities/34866) and [zChat](https://bstats.org/plugin/bukkit/zChat/21533) on bStats.
