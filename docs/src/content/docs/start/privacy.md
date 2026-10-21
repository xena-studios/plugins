---
title: Privacy
description: What the plugins send, and to whom.
sidebar:
  order: 6
---

**The current releases send nothing.** xUtilities 1.0.1, zChat 2.0.0 and xLimbo 1.0.1 make no network connections of their own. (xLimbo's `/join` asks your proxy to move a player, which never leaves your network.)

## Coming in the next major versions

The next major versions will add two things, both **on by default and easy to turn off**:

- **[bStats](https://bstats.org)**, anonymous usage statistics shared by thousands of plugins: a random server ID, player count, server software and version, Minecraft, Java and plugin versions, operating system, CPU cores, online mode, and country (worked out by bStats from the IP address, which bStats doesn't store). Turn it off for every plugin at once in `plugins/bStats/config.yml`.
- **An update checker** that asks [Modrinth](https://modrinth.com) for the plugin's latest version at startup and every 24 hours, sending only the plugin version, platform and Minecraft version. Turn it off with `update-checker.enabled: false` in the plugin's config.

Neither collects anything that identifies a person. This page will list the exact data before those versions are released.
