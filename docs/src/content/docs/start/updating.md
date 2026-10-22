---
title: Updating
description: Move to a new version without losing your settings.
sidebar:
  order: 3
---

1. Download the new version and replace the old jar in `plugins/`.
2. Restart the server.

Your `config.yml` is kept. Each config has a `config-version` key, which the plugin uses to carry older configs forward: new settings are added with their defaults, and your values stay as they are. Don't edit `config-version` by hand.

When an update changes the config's format, as xUtilities 2 and zChat 3 do, the plugin first copies your old file to `config.yml.bak-v<old version>`, such as `config.yml.bak-v1`, so you can always go back.

xUtilities 2 and zChat 3 tell you when an update is out: in the console, and to players with the plugin's admin permission (`xutilities.admin`, `zchat.admin`) when they join. `/xutilities info` and `/zchat info` show it too.

After updating, read the version's changelog on Modrinth for anything new you might want to turn on.
