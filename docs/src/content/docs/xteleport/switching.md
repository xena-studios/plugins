---
title: Switching from EssentialsX
description: Move homes, warps and the spawn from EssentialsX to xTeleport, and which EssentialsX commands to turn off.
sidebar:
  order: 4
---

xTeleport's commands have the same names as EssentialsX's, so players keep typing what they know, and it can bring everyone's homes with it.

## Bring your homes

1. Install xTeleport next to EssentialsX and start the server once. Leave EssentialsX's folder, `plugins/Essentials`, where it is: xTeleport reads it from there.
2. See what it would bring, without changing anything:

   ```
   /xteleport import essentials
   ```

   It counts the homes in `userdata/`, the warps in `warps/` and the spawn in `spawn.yml`, and lists anything it can't read.
3. Import:

   ```
   /xteleport import essentials confirm
   ```

   A home or warp you already have in xTeleport with the same name is kept, and so is a spawn set with `/setspawn`, so running it twice is safe. Names xTeleport doesn't accept (it takes letters, digits, `-` and `_`) have other characters turned into `_`: `my farm` becomes `my_farm`.
4. Remove EssentialsX, or keep it and turn off its teleport commands (below).

EssentialsX saves worlds by uuid, with the name beside it in newer versions. A home in a world that isn't loaded and has no name saved is skipped, and the import says which.

Homes aren't limited on the way in: a player who had more homes than xTeleport's limit keeps them all, and can't set more until they're under it.

## Keeping EssentialsX

If you keep EssentialsX for its other commands, two plugins will want `/home`, `/tpa` and the rest. Turn them off in EssentialsX's `config.yml`, so xTeleport's run:

```yaml
disabled-commands:
  - sethome
  - home
  - delhome
  - homes
  - tpa
  - tpahere
  - tpaccept
  - tpdeny
  - tpacancel
  - tptoggle
  - setwarp
  - warp
  - delwarp
  - warps
  - setspawn
  - spawn
  - rtp
```

Or the other way round: turn off any of xTeleport's under `commands` in its config, and it doesn't register the command at all.

Keep EssentialsSpawn out if xTeleport handles spawn, or turn off `spawn.on-first-join` and `spawn.on-respawn` in xTeleport's config: two plugins setting where players spawn is one too many.

## HuskHomes and CMI

Importing from HuskHomes and CMI is planned. Until then, players can set their homes again with `/sethome`.

## On Fabric

EssentialsX is a Bukkit plugin, so on Fabric there's nothing to import, and `/xteleport import` isn't there.

## Commands, side by side

| EssentialsX | xTeleport |
| --- | --- |
| `/sethome`, `/home`, `/delhome`, `/homes` | The same. `/home <player>:<name>` for staff. |
| `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, `/tptoggle` | The same. Several requests can wait at once. |
| `/setwarp`, `/warp`, `/delwarp`, `/warps` | The same. |
| `/setspawn`, `/spawn` | The same. |
| `essentials.sethome.multiple.<group>` | `homes.limits` in the config, or `xteleport.homes.<n>`. |
| `/back` | Not in xTeleport: [xUtilities](/xutilities/) has it, and returns from xTeleport's teleports on Bukkit. |
| `/tp`, `/tphere` | Vanilla's `/tp`. |
