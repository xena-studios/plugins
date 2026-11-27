---
title: Switching
description: "Move to xBans from LiteBans, AdvancedBan, EssentialsX or vanilla bans, and keep your bans, mutes and history."
sidebar:
  order: 5
---

xBans uses LiteBans' commands, flags and durations, so staff can carry on as before, and it imports from:

| Source | What comes across |
| --- | --- |
| `litebans` | Bans, IP bans, mutes, warnings and kicks, with who made and revoked each, their scopes and silent flags; and LiteBans' login history, for names, `/alts` and `/iphistory`. From its H2 file, MySQL or MariaDB. |
| `advancedban` | AdvancedBan's history: bans, IP bans, mutes, warnings and kicks, with who made each, and which were lifted early. From its own HSQLDB files, MySQL or MariaDB. |
| `vanilla` | `banned-players.json` and `banned-ips.json`: the server's own lists, which vanilla's and EssentialsX's `/ban` write. |
| `essentials` | EssentialsX's mutes, and each player's last name and IP. (EssentialsX keeps its bans in the server's lists: import `vanilla` for those.) |

Every import works the same way:

1. Run `/xbans import <source>` from the console (`/xbansproxy import <source>` on the proxy). It reads everything and says how much it found, without importing anything.
2. Run `/xbans import <source> confirm` to import it. It runs in the background.

Importing twice adds nothing: each row remembers where it came from. Bans and mutes in force are enforced as soon as they're in, and in [network mode](/xbans/networks/) the other servers take them in too, without a broadcast for each.

Punishments from the server's lists and EssentialsX get the default scope (`scopes.default-scope`). LiteBans' keep theirs: a global LiteBans ban (`*`) stays global, whatever the default scope. AdvancedBan's are global, as AdvancedBan has no scopes.

With `ips.enabled: false`, no IP is imported, and a ban of an IP alone is skipped. Players already online who are banned by the import are kicked.

## From LiteBans

xBans reads LiteBans' database with the settings in `plugins/LiteBans/config.yml`, so leave LiteBans' folder where it is until the import is done.

**On H2, LiteBans' default** (`driver: H2`, its database in `plugins/LiteBans/litebans.mv.db`):

1. Stop LiteBans: take its jar out of `plugins/`, keep its folder, and restart. A LiteBans that's running has its file open, and xBans says so rather than read it.
2. Run `/xbans import litebans`, then `/xbans import litebans confirm`. The driver for LiteBans' file is downloaded the first time: see [below](#reading-litebans-and-advancedbans-own-files).

**On MySQL or MariaDB** (`driver: MySQL` or `MariaDB`): run `/xbans import litebans`, then `/xbans import litebans confirm`. LiteBans can keep running for this one.

### What's different

- **Commands and flags** are the same: `-s`, `-S`, `-p`, `-g`, `-I`, `server:`, durations with `mo` and `y`, `#id` for one punishment.
- **Templates** are in xBans' `config.yml`, under `templates`, with the same idea: a ladder that climbs each time. LiteBans' `templates.yml` isn't imported, so set up the ones you use; imported punishments don't count toward a template's ladder.
- **Permissions** start with `xbans.` instead of `litebans.`: `xbans.ban`, `xbans.mute`, `xbans.history`. See [Commands](/xbans/commands/).
- **Weights** are permissions (`xbans.weight.<n>`), not group weights read through Vault.
- **Unbanning** follows weights: staff lift their own punishments and those given by staff with a weight no higher than theirs, and `xbans.unban.override` lifts any. See [Commands](/xbans/commands/#staff-limits). Imported punishments count as the console's, since their staff's weights aren't known: whoever gave one can still lift it, and so can `xbans.unban.override`.
- **Not in 2.0:** wildcard IP bans (`192.168.1.*`; the import counts them and leaves them out, since they'd match nobody), `/staffrollback`, `/lockdown`, `--modify`, GeoIP and the web interface. The proxy plugin is in: see [Networks](/xbans/networks/).

## From AdvancedBan

xBans reads AdvancedBan's database with its settings, so leave AdvancedBan's folder where it is until the import is done.

**On its own files, AdvancedBan's default** (`UseMySQL: false`, its database in `plugins/AdvancedBan/data/storage.*`): stop AdvancedBan first (take its jar out of `plugins/`, keep its folder, and restart), then run `/xbans import advancedban` and `/xbans import advancedban confirm`. The driver is downloaded as for LiteBans' file: see [below](#reading-litebans-and-advancedbans-own-files).

**On MySQL or MariaDB** (`UseMySQL: true`, the settings in `plugins/AdvancedBan/MySQL.yml`): run `/xbans import advancedban`, then `/xbans import advancedban confirm`.

Every punishment in AdvancedBan's history comes across, each with who gave it and why. One that's no longer in force but hadn't ended was lifted, so it comes across lifted: AdvancedBan doesn't keep who lifted it, so the removal says "AdvancedBan". Its notes aren't punishments, so they stay behind. Its permissions are `ab.*`; xBans' are `xbans.*`: see [Permissions](/xbans/permissions/).

## Reading LiteBans' and AdvancedBan's own files

Servers have no driver for LiteBans' H2 file or AdvancedBan's HSQLDB files, so xBans downloads the one a file needs from Maven Central the first time, into the family's library folder, `plugins/Xena/libraries/`, checked against the hash pinned in xBans' build, and only when such an import runs: nothing is downloaded or loaded at startup. It copies the files, reads the copy with the driver in a class loader of its own, then deletes the copy and lets the driver go. The plugin's own files are never written to.

H2's files come in three formats, and each H2 reads only its own, so xBans looks at the file's first line and picks the driver, whichever H2 your LiteBans shipped:

| File written by | Read with |
| --- | --- |
| H2 1.4 (`format:1` in the file's first line, or an older `litebans.h2.db`) | H2 1.4.200 |
| H2 2.0 or 2.1 (`format:2`) | H2 2.1.214 |
| H2 2.2 or later (`format:3`) | H2 2.4.240, which needs Java 11 or newer |
| HSQLDB 2.x (AdvancedBan's `data/storage.*`) | HSQLDB 2.5.2 |

That covers every LiteBans on H2 (from 2.1, which moved LiteBans from SQLite to H2) and every AdvancedBan 2.x (HSQLDB 2.4.0 since AdvancedBan 2.2.0). The tests read files written by H2 1.4.199, 2.0.206 and 2.2.224 and by HSQLDB 2.4.0, with LiteBans' and AdvancedBan's tables. LiteBans' H2 file is opened with `sql.username` and `sql.password` from its `config.yml`, then with H2's default login. A server that can't reach Maven Central says which jar to put where; for example, put `h2-2.1.214.jar` from Maven Central at `plugins/Xena/libraries/com/h2database/h2/2.1.214/h2-2.1.214.jar` and run the import again. `mirror:` in `plugins/Xena/libraries.yml` downloads from somewhere else.

## From vanilla or EssentialsX

Run `/xbans import vanilla confirm` for the server's ban lists, then `/xbans import essentials confirm` for EssentialsX's mutes. The server's own lists are left as they were; you can empty them with vanilla's commands afterwards (`/minecraft:pardon`), since xBans enforces the imported bans itself.

## Not yet

An import from LibertyBans comes after 2.0.
