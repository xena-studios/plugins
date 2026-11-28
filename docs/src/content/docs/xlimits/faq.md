---
title: FAQ
description: "Common questions about xLimits."
sidebar:
  order: 20
---

**Will it delete my players' animals?** No. Caps refuse new spawns and placements; farm slowing changes how mobs move and breed, and changes them back. Nothing is removed.

**How do I remove xLimits?** Run `/xlimits restore` first (typed twice), which puts every slowed mob in the loaded chunks back and keeps restoring chunks as they load until a restart. Mobs carry the `xlimits.slowed` tag, so `/tag @e[tag=xlimits.slowed] list` finds any left; restoring needs xLimits, so run it before you take the jar out.

**My iron farm stopped working.** Villagers are never slowed unless `farms.villagers` is on. Check `/xlimits here` in the farm for a `villagers` or `iron_golem` cap it reaches.

**A player says their spawn egg does nothing.** They're told why ("This chunk has 40 of 40 animals"); give builders `xlimits.bypass` if they need to go past caps.

**Does it work with plot and claim plugins?** Through the API: a plot or claim plugin can register a [limit provider](/xlimits/developer-api/) that changes the limits in its areas. Limits stay per chunk; xPlots doesn't register one yet.

**Why didn't it catch the first hopper in a chunk?** The first block xLimits sees placed in a chunk starts that chunk's count, off the server's threads; until it's done (a moment), placements are allowed and added to it after.
