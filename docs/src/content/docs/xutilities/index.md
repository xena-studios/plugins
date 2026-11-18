---
title: xUtilities
description: "xUtilities: lightweight essentials for Paper and Folia servers, with gamemode, fly, heal, /back, workstations anywhere and your own link commands, each one switchable."
sidebar:
  label: Overview
  order: 1
---

xUtilities gives a server the small commands it needs: gamemodes, flight, speed, `/top`, `/back`, heal and feed, inventory tools, workstation menus, and link commands like `/discord` that you write yourself. Install it on your lobby, survival and minigame servers alike, then switch off whatever a server doesn't use.

- **Every feature free.** No premium version, no early-access builds.
- **Only what you want.** Every command can be turned off, renamed with aliases, given its own permission, and reworded. A command that's off isn't registered at all, so it doesn't show up in tab completion and another plugin can have its name.
- **You won't feel it running.** Nothing runs on a timer but the daily update check and bStats, and you can turn both off. `/back`'s listener exists only while `/back` is on.
- **It doesn't stop over a typo.** A bad value in the config falls back to its default with a warning in the console.
- **Every change applies on reload**, even turning commands on or off and adding link commands. No restarts.

:::note[Coming from xUtilities 1.x?]
Replace the jar and restart. Your config is carried forward to 2.0's format, and the old file is kept beside it as `config.yml.bak-v1`. xUtilities 2 needs Paper 1.21.11 or newer.
:::

## What else it does

- **Paper and Folia**, from the same jar.

## Getting started

1. [Install it](/start/installing/).
2. See what each command does and which permission it needs: [Commands and permissions](/xutilities/commands/).
3. Replace the example `/discord`, `/store` and `/website` links with yours: [Link commands](/xutilities/link-commands/).
4. Turn off what you don't need: [Configuration](/xutilities/configuration/).
