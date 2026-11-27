---
title: Supported versions
description: "Which versions of each plugin get fixes, for how long, on which platforms, and how fast security fixes ship."
sidebar:
  order: 3.5
---

## Which versions get fixes

**The newest release of each plugin gets fixes.** Update from the plugin's **Download** page, such as [xUtilities' download](/xutilities/download/); [Updating](/start/updating/) says how.

Every plugin is being rewritten on one shared core, and the rewrites are released together, each as its plugin's next major version: xUtilities 3.0, zChat 4.0, xLimbo 3.0, and 2.0 for the others. Until then:

- **Today's versions keep getting fixes**, released as usual. These docs describe them.
- **Once the rewrites are released**, the version before each rewrite (xUtilities 2.x, zChat 3.x, xLimbo 2.x and 1.x of the others) still gets fixes for about a month, so you can update when it suits you. Its docs stay up, at `/<plugin>/v<major>/`, with a note that it's the old version.
- **Older versions get no fixes:** xUtilities 1.x, zChat 1.x and 2.x, and xLimbo 1.x.

A fix release never changes how a plugin behaves beyond the fix, and never needs you to change your config.

## Security fixes

Report a security problem privately, as [SECURITY.md](https://github.com/xena-studios/plugins/blob/main/SECURITY.md) says, never in a public issue. You'll hear back within **72 hours** and get an initial assessment within **7 days**. A security fix is released as soon as it's ready, ahead of other work, with a GitHub security advisory, for every version that still gets fixes.

## Platforms and Minecraft versions

Each plugin's overview lists where it runs: today's versions run on Paper, Purpur and Folia (most of them) from Minecraft 1.21.11; see [Requirements](/start/requirements/). The rewrites run on Paper, Folia, Spigot down to 1.8.8 and Fabric, plus Velocity and BungeeCord where a plugin has network features; more platforms follow as minor versions.

- **New Minecraft versions** are supported as soon as they're tested; each plugin is started on real servers in CI, on the oldest and newest Minecraft it supports.
- **A platform is dropped** only when it's under 1% of the plugin's servers (by bStats) for six months, and it's announced a major version ahead.

## Developer APIs

Each plugin's developer API has its own version. A minor or fix release never breaks it: the build checks every public signature against a committed file. Anything deprecated keeps working until the next major version. See [Developers](/developers/).
