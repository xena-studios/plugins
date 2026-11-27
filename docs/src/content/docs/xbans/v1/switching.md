---
title: Switching
description: Move to xBans from LiteBans, EssentialsX or vanilla bans, and keep your bans, mutes and history.
sidebar:
  order: 5
---

xBans uses LiteBans' commands, flags and durations, so staff can carry on as before, and it imports from:

| Source | What comes across |
| --- | --- |
| `litebans` | Bans, IP bans, mutes, warnings and kicks, with who made and revoked each, their scopes and silent flags; and LiteBans' login history, for names, `/alts` and `/iphistory`. |
| `vanilla` | `banned-players.json` and `banned-ips.json`: the server's own lists, which vanilla's and EssentialsX's `/ban` write. |
| `essentials` | EssentialsX's mutes, and each player's last name and IP. (EssentialsX keeps its bans in the server's lists: import `vanilla` for those.) |

Every import works the same way:

1. Run `/xbans import <source>` from the console. It reads everything and says how much it found, without importing anything.
2. Run `/xbans import <source> confirm` to import it. It runs in the background.

Importing twice adds nothing: each row remembers where it came from. Bans and mutes in force are enforced as soon as they're in, and in [network mode](/xbans/v1/networks/) the other servers take them in too, without a broadcast for each.

Punishments from the server's lists and EssentialsX get the default scope (`scopes.default-scope`). LiteBans' keep theirs: a global LiteBans ban (`*`) stays global, whatever the default scope.

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
- **Permissions** start with `xbans.` instead of `litebans.`: `xbans.ban`, `xbans.mute`, `xbans.history`. See [Commands](/xbans/v1/commands/).
- **Weights** are permissions (`xbans.weight.<n>`), not group weights read through Vault.
- **Unbanning** follows weights: staff lift their own punishments and those given by staff with a weight no higher than theirs, and `xbans.unban.override` lifts any. See [Commands](/xbans/v1/commands/#staff-limits). Imported punishments count as the console's, since their staff's weights aren't known: whoever gave one can still lift it, and so can `xbans.unban.override`.
- **Not in 1.0:** wildcard IP bans (`192.168.1.*`; the import counts them and leaves them out, since they'd match nobody), `/staffrollback`, `/lockdown`, `--modify`, GeoIP, the web interface and a proxy plugin.

## From vanilla or EssentialsX

Run `/xbans import vanilla confirm` for the server's ban lists, then `/xbans import essentials confirm` for EssentialsX's mutes. The server's own lists are left as they were; you can empty them with vanilla's commands afterwards (`/minecraft:pardon`), since xBans enforces the imported bans itself.

## Not yet

Imports from AdvancedBan and LibertyBans come after 1.0.
