---
title: Permissions
description: How permissions and their defaults work across the plugins.
sidebar:
  order: 4
---

Every command has a permission, and every permission has a safe default:

- **Staff and state-changing commands default to `op`.** Out of the box, only operators can use them.
- **Harmless commands default to everyone**: xUtilities' `/ping` and link commands, zChat's `/togglechat`, and xLimbo's `/join`.
- **Acting on another player needs a separate `.others` permission**, also `op` by default. Giving a rank `/heal` doesn't let it heal other players.

Grant them to your ranks with a permissions plugin such as [LuckPerms](https://luckperms.net):

```
/lp group helper permission set xutilities.command.fly true
/lp group helper permission set xutilities.command.fly.others true
```

## Changing a command's permission

Each command's permission is set in its plugin's config, so you can rename it to fit your setup. Two rules:

- **Blank (`""`) means no restriction**: anyone can use the command.
- A permission you invent that the plugin doesn't declare defaults to `op` until you grant it.

The full lists: [xUtilities](/xutilities/commands/), [zChat](/zchat/commands/), [xLimbo](/xlimbo/commands/).
