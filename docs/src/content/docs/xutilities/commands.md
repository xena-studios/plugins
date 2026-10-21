---
title: Commands and permissions
description: Every xUtilities command, its aliases, and the permissions it needs.
sidebar:
  order: 2
---

`[player]` means the command can act on someone else, which needs the `.others` permission. Without a player, it acts on you. Commands that act only on you can't be run from the console; the `[player]` forms can.

Every permission below defaults to `op` unless it says **everyone**. Aliases are the defaults; change them in the [config](/xutilities/configuration/).

## Gamemode

| Command | Aliases | Permission |
| --- | --- | --- |
| `/survival [player]` | `gms`, `gm0` | `xutilities.command.survival` |
| `/creative [player]` | `gmc`, `gm1` | `xutilities.command.creative` |
| `/adventure [player]` | `gma`, `gm2` | `xutilities.command.adventure` |
| `/spectator [player]` | `gmsp`, `gm3` | `xutilities.command.spectator` |
| `/gamemode <mode> [player]` | `gm` | `xutilities.command.gamemode` |

Each has a matching `.others` permission, such as `xutilities.command.survival.others`. `/gamemode` offers only the modes whose own command is turned on.

## Moving around

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/fly [player]` | `flight`, `togglefly`, `toggleflymode` | `xutilities.command.fly` | Turns flight on or off. |
| `/speed <speed> [player]` | `setspeed` | `xutilities.command.speed` | Sets speed from 1 to 10: fly speed while flying, walk speed otherwise. |
| `/top` | | `xutilities.command.top` | Teleports you to the highest safe block above you. |
| `/back` | | `xutilities.command.back` | Takes you back to where you were before your last teleport, or where you died. |
| `/setrotation <yaw> <pitch> [player]` | | `xutilities.command.setrotation` | Sets which way a player faces. |
| `/ping [player]` | | `xutilities.command.ping` (**everyone**) | Shows network latency in milliseconds. |

`/fly`, `/speed`, `/setrotation` and `/ping` have `.others` permissions. `/top` and `/back` act only on you.

## Health and inventory

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/heal [player]` | | `xutilities.command.heal` | Restores health, and by default hunger; puts out fire and clears harmful effects. |
| `/feed [player]` | | `xutilities.command.feed` | Restores hunger. |
| `/repair [all] [player]` | `fix` | `xutilities.command.repair` | Repairs the held item, or with `all`, the whole inventory. |
| `/clear [player]` | `clearinventory`, `ci` | `xutilities.command.clear` | Empties the inventory, including armour and off-hand by default. |
| `/enderchest [player]` | `ec`, `ender` | `xutilities.command.enderchest` | Opens an Ender Chest: yours, or another player's. |

All five have `.others` permissions.

## Workstations

These open the real Minecraft menu without the block placed. They act only on you.

| Command | Aliases | Permission |
| --- | --- | --- |
| `/anvil` | | `xutilities.command.anvil` |
| `/craft` | `workbench`, `wb` | `xutilities.command.craft` |
| `/grinder` | `grindstone` | `xutilities.command.grinder` |
| `/loom` | | `xutilities.command.loom` |
| `/smith` | `smithingtable`, `smithtable` | `xutilities.command.smith` |
| `/trash` | `disposal` | `xutilities.command.trash` |

`/trash` opens an empty inventory; anything left in it is deleted when it closes.

## Link commands

`/discord`, `/store`, `/website`, and any you add. Each uses `xutilities.command.<name>`, which defaults to **everyone**. See [Link commands](/xutilities/link-commands/).

## Admin

| Command | Permission | What it does |
| --- | --- | --- |
| `/xutilities reload` | `xutilities.admin` | Re-reads the config and applies it. See [what needs a restart](/start/installing/#reload-or-restart). |
| `/xutilities info` | `xutilities.admin` | Shows the version and status. |
