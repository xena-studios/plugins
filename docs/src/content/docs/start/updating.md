---
title: Updating
description: Move to a new version without losing your settings.
sidebar:
  order: 3
---

1. Download the new version and replace the old jar in `plugins/`.
2. Restart the server.

Your `config.yml` is kept. Each config has a `config-version` key, which the plugin uses to carry older configs forward: new settings are added with their defaults, and your values stay as they are. Don't edit `config-version` by hand.

When an update changes the config's format, as xUtilities 2, zChat 3 and xLimbo 2 do, the plugin first copies your old file to `config.yml.bak-v<old version>`, such as `config.yml.bak-v1`, so you can always go back.

Every plugin tells you when an update is out: in the console, and to players with its admin permission (`<plugin>.admin`, such as `xbans.admin`) when they join. `/<plugin> info` shows it too. The check asks Modrinth, so a plugin Modrinth is still reviewing can't see its own updates yet: watch its [releases on GitHub](https://github.com/xena-studios/plugins/releases) until then.

After updating, read the plugin's **Changelog** page, such as [xUtilities' changelog](/xutilities/changelog/), for anything new you might want to turn on.
