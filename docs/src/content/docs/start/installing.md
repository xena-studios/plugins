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

**Every plugin applies everything on reload**, including turning commands on or off, aliases and permissions. xLimbo even rebuilds its world when its name or floor changes.

## A bad value never breaks the plugin

If a config value is invalid, the plugin logs a warning in the console and uses the default for that one value. It never disables itself or stops the server over config.

If the whole file can't be read, such as a YAML syntax error, the plugin says where in the console and leaves the file alone. On a reload it keeps running with the settings it had; at startup it runs on the defaults until you fix the file and reload.
