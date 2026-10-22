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

That's the plugin and its version, the platform (`paper`, or `folia` on Folia) and the Minecraft version. Like any web request, it comes from your server's IP address; Modrinth's [privacy policy](https://modrinth.com/legal/privacy) covers what Modrinth does with it. Nothing about your players is sent. zChat and xLimbo send the same, with their own name in place of `xutilities`.

## bStats

xUtilities 2, zChat 3 and xLimbo 2 send **[bStats](https://bstats.org)**, anonymous usage statistics shared by thousands of plugins, on by default: a random server ID, player count, server software and version, Minecraft, Java and plugin versions, operating system, CPU cores, online mode, and country (worked out by bStats from the IP address, which bStats doesn't store). Turn it off for every plugin at once in `plugins/bStats/config.yml`. (zChat 1.x sent bStats too; zChat 2 and xLimbo 1 didn't.) The numbers are public: [xUtilities](https://bstats.org/plugin/bukkit/XenaUtilities/34866), [zChat](https://bstats.org/plugin/bukkit/zChat/21533) and [xLimbo](https://bstats.org/plugin/bukkit/xLimbo/34867) on bStats.

## Inside your network

xLimbo's `/join` and automatic returns ask your proxy to move a player, which never leaves your network. With [server-check](/xlimbo/network-setup/#send-everyone-back-when-its-up) on, xLimbo also asks your main server for its status, at the address you give it.
