---
title: Installing
description: Install a plugin, configure it, and apply changes.
sidebar:
  order: 2
---

1. Download the plugin from its **Download** page, such as [xUtilities' download](/xutilities/download/). The jar comes from Modrinth, or from the plugin's GitHub release while Modrinth is still reviewing it.
2. Put the jar in your server's `plugins/` folder.
3. Start the server once. The plugin writes its default `config.yml` to its folder in `plugins/`, such as `plugins/xBans/`.
4. Edit the config, then apply it with the plugin's reload command, `/<plugin> reload` (such as `/xbans reload`).
5. Give your ranks the permissions they need: see [Permissions](/start/permissions/).

## Reload or restart?

**Every plugin applies its settings on reload**, including turning commands on or off, aliases and permissions. xLimbo even rebuilds its world when its name or floor changes. Only where some plugins keep their data waits for a restart: `storage` in xPlots, xTeleport, xVote, xMoney and xStats, and `network` in xVote, xMoney and xStats (and xStats' `server-name`).

## A bad value never breaks the plugin

If a config value is invalid, the plugin logs a warning in the console and uses the default for that one value. It never disables itself or stops the server over config.

If the whole file can't be read, such as a YAML syntax error, the plugin says where in the console and leaves the file alone. On a reload it keeps running with the settings it had; at startup it runs on the defaults until you fix the file and reload.
