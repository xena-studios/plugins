---
title: Switching
description: "Move to xBans from LiteBans, AdvancedBan, EssentialsX or vanilla bans, and keep your bans, mutes and history."
sidebar:
  order: 5
---

xBans uses LiteBans' commands, flags and durations, so staff can carry on as before, and it imports from:

| Source | What comes across |
| --- | --- |
| `litebans` | Bans, IP bans, mutes, warnings and kicks, with who made and revoked each, their scopes and silent flags; and LiteBans' login history, for names, `/alts` and `/iphistory`. |
| `advancedban` | AdvancedBan's history: bans, IP bans, mutes, warnings and kicks, with who made each, and which were lifted early. |
| `vanilla` | `banned-players.json` and `banned-ips.json`: the server's own lists, which vanilla's and EssentialsX's `/ban` write. |
| `essentials` | EssentialsX's mutes, and each player's last name and IP. (EssentialsX keeps its bans in the server's lists: import `vanilla` for those.) |

Every import works the same way:

1. Run `/xbans import <source>` from the console (`/xbansproxy import <source>` on the proxy). It reads everything and says how much it found, without importing anything.
2. Run `/xbans import <source> confirm` to import it. It runs in the background.

Importing twice adds nothing: each row remembers where it came from. Bans and mutes in force are enforced as soon as they're in, and in [network mode](/xbans/networks/) the other servers take them in too, without a broadcast for each.

Punishments from the server's lists and EssentialsX get the default scope (`scopes.default-scope`). LiteBans' keep theirs: a global LiteBans ban (`*`) stays global, whatever the default scope. AdvancedBan's are global, as AdvancedBan has no scopes.

With `ips.enabled: false`, no IP is imported, and a ban of an IP alone is skipped. Players already online who are banned by the import are kicked.

## From LiteBans

xBans reads LiteBans' database with the settings in `plugins/LiteBans/config.yml`, so leave that file where it is until the import is done.

**On MySQL or MariaDB** (`driver: MySQL` or `MariaDB` in LiteBans' config): run `/xbans import litebans`, then `/xbans import litebans confirm`.

**On H2, LiteBans' default.** Paper has no H2 driver, so move LiteBans to MySQL or MariaDB first with LiteBans' own import, then import into xBans:

1. Point LiteBans at an empty MySQL or MariaDB database in its `config.yml` (`sql.driver: MySQL`) and run `/litebans reload`. `/litebans info` should say it's connected to MySQL.
2. In LiteBans' `config.yml`, set `import.from: litebans` and `import.sqlite: true` (for LiteBans, this means its H2 file), then run `/litebans import start`.
3. When it's done, run `/xbans import litebans confirm`.
4. Remove LiteBans. xBans takes over `/ban`, `/mute` and the rest.

If LiteBans asks for a user name and password for the H2 file, they're in its `h2.yml`; see [LiteBans' importing guide](https://gitlab.com/ruany/LiteBans/-/wikis/Importing).

### What's different

- **Commands and flags** are the same: `-s`, `-S`, `-p`, `-g`, `-I`, `server:`, durations with `mo` and `y`, `#id` for one punishment.
- **Templates** are in xBans' `config.yml`, under `templates`, with the same idea: a ladder that climbs each time. LiteBans' `templates.yml` isn't imported, so set up the ones you use; imported punishments don't count toward a template's ladder.
- **Permissions** start with `xbans.` instead of `litebans.`: `xbans.ban`, `xbans.mute`, `xbans.history`. See [Commands](/xbans/commands/).
- **Weights** are permissions (`xbans.weight.<n>`), not group weights read through Vault.
- **Unbanning** follows weights: staff lift their own punishments and those given by staff with a weight no higher than theirs, and `xbans.unban.override` lifts any. See [Commands](/xbans/commands/#staff-limits). Imported punishments count as the console's, since their staff's weights aren't known: whoever gave one can still lift it, and so can `xbans.unban.override`.
- **Not in 2.0:** wildcard IP bans (`192.168.1.*`; the import counts them and leaves them out, since they'd match nobody), `/staffrollback`, `/lockdown`, `--modify`, GeoIP and the web interface. The proxy plugin is in: see [Networks](/xbans/networks/).

## From AdvancedBan

xBans reads AdvancedBan from MySQL or MariaDB, with the settings in `plugins/AdvancedBan/MySQL.yml` (`UseMySQL: true` in AdvancedBan's `config.yml`), so leave those files where they are until the import is done. Run `/xbans import advancedban`, then `/xbans import advancedban confirm`.

AdvancedBan's own default is a HSQLDB file in its folder, which xBans can't read: servers have no driver for it. Only an AdvancedBan on MySQL or MariaDB can be imported.

Every punishment in AdvancedBan's history comes across, each with who gave it and why. One that's no longer in force but hadn't ended was lifted, so it comes across lifted: AdvancedBan doesn't keep who lifted it, so the removal says "AdvancedBan". Its notes aren't punishments, so they stay behind. Its permissions are `ab.*`; xBans' are `xbans.*`: see [Permissions](/xbans/permissions/).

## From vanilla or EssentialsX

Run `/xbans import vanilla confirm` for the server's ban lists, then `/xbans import essentials confirm` for EssentialsX's mutes. The server's own lists are left as they were; you can empty them with vanilla's commands afterwards (`/minecraft:pardon`), since xBans enforces the imported bans itself.

## Not yet

An import from LibertyBans comes after 2.0.
