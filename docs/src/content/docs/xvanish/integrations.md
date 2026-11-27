---
title: Integrations
description: "How xVanish works with zChat, xTeleport, xBans and the other Xena plugins, and with plugins that check canSee or the vanished metadata."
sidebar:
  order: 7
---

Each integration switches on by itself when the other plugin is there.

| Plugin | What it does with a vanished player |
| --- | --- |
| [zChat](/zchat/) | Not mentioned, completed or reachable by `/msg` for anyone who can't see them: on Bukkit through the server's `canSee`, on Fabric through zChat's own hidden-players hook, which xVanish registers. |
| [xTeleport](/xteleport/) | `/tpa` and `/tpahere` can't find them; their name isn't completed. xTeleport reads the family's player cache, where xVanish marks who's vanished. |
| [xBans](/xbans/) | Punishments work as usual. The players xBans counts as staff (`xbans.staff`) see level-1 vanished players, with `vanish.xbans-staff-see`. |
| Every Xena plugin | The family's player cache says who's vanished, so lists and counts can leave them out. |

## Other plugins

On Bukkit a vanished player is hidden with Bukkit's own `hidePlayer`, so any plugin that asks `canSee` before naming a player leaves them out. xVanish also sets the `vanished` metadata on them (`player.getMetadata("vanished")`, `true`), which many plugins check, as SuperVanish set it. A plugin that lists players without either (some maps, Discord bridges and scoreboards) shows them: use `%xvanish_online%` for counts, and check the plugin's own vanish setting.
