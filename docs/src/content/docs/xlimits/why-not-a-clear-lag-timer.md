---
title: Why not a clear-lag timer
description: "Why xLimits limits and slows instead of wiping entities every few minutes."
sidebar:
  order: 8
---

Plugins that clear entities on a timer (ClearLagg and its heirs) are the usual first answer to a laggy server, and the usual first regret.

- **They delete what players own.** A timer can't tell a dropped stack from a player's shop display, a farm's output or their animals. Players learn to distrust the server.
- **They don't stop the cause.** The farm that made 400 chickens makes 400 more before the next sweep. The server lags in a saw-tooth instead of all the time.
- **The sweep is the lag.** Scanning every entity in every world every few minutes is a spike of its own.

xLimits works the other way round: **refuse what's new, at the moment it tries to happen, and slow what's crammed.** A full chunk doesn't get another cow; a crammed pen stops colliding; a hopper wall stops at its cap with a message saying why. Nothing that exists is removed, and nothing runs on a timer.

It isn't a "lag fixer" either. If the hardware is slow, nothing a plugin does makes it fast. What xLimits does is keep one player's build from costing everyone else, and show staff where the load is (`/xlimits top`), so they fix the cause.
