---
title: FAQ
description: "Questions about xVanish: why a plugin still shows a vanished player, read-only containers, Fabric and Folia."
sidebar:
  order: 9
---

**A plugin still lists a vanished player.** It doesn't ask Bukkit's `canSee` or check the `vanished` metadata. See [Integrations](/xvanish/integrations/).

**Why can't I take items from a chest while vanished?** Silent containers open a read-only copy, so nobody sees the lid or hears it. Sneak and open it to use it as usual (and be heard).

**Does it work on Folia?** Yes: each player is hidden and shown on their own thread, and a player vanishing is hidden from everyone at once first, so nobody on another region sees them for a moment.

**What does Fabric miss?** Fabric has no events for the server list, mob targeting, pickup, pressure plates, sculk, raids, containers, collisions, sleeping or spawning, so those switches do nothing there. Hiding, the messages and the rest work.

**Does it use a database?** Not on a single server: `vanished.txt` holds who stays vanished. Only network mode uses one.
