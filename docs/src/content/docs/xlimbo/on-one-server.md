---
title: On one server
description: "xLimbo as a world on a server players play on: /xlimbo send, and back through xTeleport's destinations, with xVanish's hidden players left out."
sidebar:
  order: 3.5
---

New in 3.0. With `send-back.via: teleport`, xLimbo isn't a network's fallback but a world on a server players play on: a waiting room for maintenance, an AFK corner, somewhere to hold players until another plugin lets them go. Nobody is put there by joining; they're sent:

- **`/xlimbo send <player>`**, for staff;
- **the API's `sendToLimbo`**, for plugins ([Developer API](/xlimbo/developer-api/));
- **xTeleport's `xlimbo` destination**: xLimbo gives xTeleport a destination by that name, so any plugin using xTeleport's API sends a player into limbo with `XTeleport.api().teleport(player, "xlimbo", options)`, with xTeleport's warmup, cooldown and cost if it asks for them.

```yaml
send-back:
  via: teleport
  destination: previous
```

## Going back

`/join`, auto-join's delay, `/xlimbo back` and the API all send players back with a teleport to `destination`:

| `destination` | Where |
| --- | --- |
| `previous` | Where they were before limbo. If that isn't known (the server restarted while they were there), `spawn`. |
| `spawn` | xTeleport's `/spawn` (the one `/setspawn` set, or the main world's). Without xTeleport, the main world's spawn point. |
| `warp:<name>` | One of xTeleport's warps. |
| any other name | A destination another plugin gave xTeleport (`registerDestination`), such as a plot's entrance. |

With **[xTeleport](/xteleport/)** installed, every teleport back goes through its API: no warmup, cooldown or cost, and its safety check, so nobody lands in lava. Without it, `previous` and `spawn` are Paper's own teleports, and a warp or a named destination can't be reached: the player stays and the console says why.

Server-check doesn't apply here (there's no other server to watch); auto-join and the queue do.

## What players get

- **Adventure mode, not creative.** A player of this server keeps nothing from limbo: in creative they could take items back with them. With `player.set-creative` on (the default), they're put in adventure, which can't break or place either. `enable-flight` still gives them flight.
- **Their game mode back as they leave**, with their flight: what they had before limbo, or the server's default game mode if xLimbo doesn't know (it restarted while they were there).
- **Only players in limbo are hidden** from each other with `hide-others`, and only their join, quit and death messages are left out. Everyone else on the server is as before.
- The protections act in the limbo world only, as always.

## xVanish

With **[xVanish](/xvanish/)** installed, a vanished player in limbo is left out wherever another player could count them: `/xlimbo`'s count, `/xlimbo list`, and the queue's place and total on the action bar (they see their own place, among the players everyone can see). Staff who can see them (xVanish's levels and `xvanish.see`) see them listed as anywhere else. Without xVanish, the family's player cache says who's vanished, and `xvanish.see` who sees them. This is the same in either mode.
