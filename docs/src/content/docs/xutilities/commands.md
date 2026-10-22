---
title: Commands and permissions
description: Every xUtilities command, its aliases, and the permissions it needs.
sidebar:
  order: 2
---

`[player]` means the command can act on someone else, which needs the `.others` permission. Without a player, it acts on you. Commands that act only on you can't be run from the console; the `[player]` forms can. A player changed by someone else is told, with the same message they'd get doing it themselves.

Players you can't see, such as staff who are vanished, count as offline: you can't target them or tab-complete their names.

Every permission below defaults to `op` unless it says **everyone**. Aliases are the defaults; change them in the [config](/xutilities/configuration/).

## Gamemode

| Command | Aliases | Permission |
| --- | --- | --- |
| `/survival [player]` | `gms`, `gm0` | `xutilities.command.survival` |
| `/creative [player]` | `gmc`, `gm1` | `xutilities.command.creative` |
| `/adventure [player]` | `gma`, `gm2` | `xutilities.command.adventure` |
| `/spectator [player]` | `gmsp`, `gm3` | `xutilities.command.spectator` |
| `/gamemode <mode> [player]` | `gm` | `xutilities.command.gamemode` |

Each has a matching `.others` permission, such as `xutilities.command.survival.others`.

`/gamemode` replaces Minecraft's own. It offers only the modes whose own command is turned on, and needs that command's permission as well as its own, so giving a rank `/gamemode` doesn't give it creative. `<mode>` can be a name (`creative`), a number (`1`), a letter (`c`, or `sp` for spectator), or one of the mode's aliases (`gmc`). Acting on another player needs `xutilities.command.gamemode.others`.

## Moving around

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/fly [player]` | `flight`, `togglefly`, `toggleflymode` | `xutilities.command.fly` | Turns flight on or off. |
| `/speed <speed> [player]` | `setspeed` | `xutilities.command.speed` | Sets speed from 1 to 10: fly speed while flying, walk speed otherwise. Walking is 2 by default. |
| `/top` | | `xutilities.command.top` | Teleports you to the highest block above you, unless it's lava, fire, magma or similar. In the Nether, the highest spot below the roof. |
| `/back` | | `xutilities.command.back` | Takes you back to where you were before your last teleport, or where you died. |
| `/setrotation <yaw> <pitch> [player]` | | `xutilities.command.setrotation` | Sets which way a player faces. |
| `/ping [player]` | | `xutilities.command.ping` (**everyone**) | Shows network latency in milliseconds. |

`/fly`, `/speed`, `/setrotation` and `/ping` have `.others` permissions. `/top` and `/back` act only on you.

`/back` remembers teleports by commands and plugins, not Ender Pearls or portals, and forgets a player when they log out. Running it again takes you back to where you ran it.

## Health and inventory

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/heal [player]` | | `xutilities.command.heal` | Restores health, and by default hunger; puts out fire and clears harmful effects. |
| `/feed [player]` | | `xutilities.command.feed` | Restores hunger. |
| `/repair [all] [player]` | `fix` | `xutilities.command.repair` | Repairs the held item, or with `all`, the whole inventory. |
| `/clear [player]` | `clearinventory`, `ci` | `xutilities.command.clear` | Empties the inventory, including armour and off-hand by default. Replaces Minecraft's `/clear`. |
| `/enderchest [player]` | `ec`, `ender` | `xutilities.command.enderchest` | Opens an Ender Chest: yours, or another player's. |

All five have `.others` permissions. Turn off `/clear` or `/gamemode` and Minecraft's own comes back.

On Folia, `/enderchest <player>` opens another player's chest only when they're nearby (in the same region as you), since Folia can't safely share an inventory between regions. Otherwise you get `message-too-far`.

## Workstations

These open the real Minecraft menu without the block placed. They act only on you.

| Command | Aliases | Permission |
| --- | --- | --- |
| `/anvil` | | `xutilities.command.anvil` |
| `/craft` | `workbench`, `wb` | `xutilities.command.craft` |
| `/grinder` | `grindstone` | `xutilities.command.grinder` |
| `/loom` | | `xutilities.command.loom` |
| `/smith` | `smithingtable`, `smithtable` | `xutilities.command.smith` |
| `/stonecutter` | | `xutilities.command.stonecutter` |
| `/cartography` | `cartographytable` | `xutilities.command.cartography` |
| `/trash` | `disposal` | `xutilities.command.trash` |

`/trash` opens an empty inventory; anything left in it is deleted when it closes.

Each menu can send a message and play a sound as it opens; both are off by default, since the menu itself shows it worked. `/enderchest` plays the Ender Chest's opening sound and `/trash` a barrel's, as the real blocks would. Set them in the [config](/xutilities/configuration/).

## Link commands

`/discord`, `/store`, `/website`, and any you add. Each uses `xutilities.command.<name>`, which defaults to **everyone**. See [Link commands](/xutilities/link-commands/).

## Admin

| Command | Permission | What it does |
| --- | --- | --- |
| `/xutilities reload` | `xutilities.admin` | Re-reads the config and applies all of it, commands included. |
| `/xutilities info` | `xutilities.admin` | Shows the version, which commands are on, whether an update is out, and any config problems. |

`/xu` is short for `/xutilities`: `/xu reload` works the same.

## If another plugin has the same command

If another plugin already has a name or alias xUtilities wants, such as `/fly`, the other plugin keeps it, and the console says so. xUtilities' command is still there as `/xutilities:fly`. To use ours under the plain name, turn the other plugin's command off, or give ours a different alias.
