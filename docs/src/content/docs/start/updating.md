---
title: Updating
description: Move to a new version without losing your settings.
sidebar:
  order: 3
---

1. Download the new version and replace the old jar in `plugins/`.
2. Restart the server.

Your `config.yml` is kept. Each config has a `config-version` key, which the plugin uses to carry older configs forward: new settings are added with their defaults, and your values stay as they are. Don't edit `config-version` by hand.

After updating, read the version's changelog on Modrinth for anything new you might want to turn on.
