---
title: Installing
description: Install a plugin, configure it, and apply changes.
sidebar:
  order: 2
---

1. Download the plugin from its Modrinth page: [xUtilities](https://modrinth.com/plugin/xutilities), [zChat](https://modrinth.com/plugin/zchat) or [xLimbo](https://modrinth.com/plugin/xlimbo).
2. Put the jar in your server's `plugins/` folder.
3. Start the server once. The plugin writes its default `config.yml` to `plugins/<plugin>/`.
4. Edit the config, then apply it with the plugin's reload command (`/xutilities reload`, `/zchat reload`, `/xlimbo reload`).
5. Give your ranks the permissions they need: see [Permissions](/start/permissions/).

## Reload or restart?

Most settings apply live on reload: every message and most behaviour. A few are read only when the server starts, because Paper registers commands at startup:

- turning a command on or off;
- a command's aliases and permission;
- for xUtilities, adding, removing or renaming a [link command](/xutilities/link-commands/);
- for xLimbo, the world's name, floor height and floor block.

Change one of those and restart the server.

## A bad value never breaks the plugin

If a config value is invalid, the plugin logs a warning in the console and uses the default for that one value. It never disables itself or stops the server over config.
