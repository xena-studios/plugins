---
title: Commands
description: xBans' commands, flags, durations and permissions, the same as LiteBans'.
sidebar:
  order: 2
---

The commands and flags are LiteBans', so what your staff know already works. Every permission is given to operators by default except `/warnings` (everyone may see their own) and the limits below.

## Punishing

| Command | What it does | Permission |
| --- | --- | --- |
| `/ban <player> [duration] [reason]` | Bans, permanently or for the duration. | `xbans.ban` |
| `/tempban <player> <duration> [reason]` | Bans for a while. The duration is required. | `xbans.tempban` |
| `/ipban <player\|ip> [duration] [reason]` (`/banip`, `/ban-ip`) | Bans the player's account and their last IP, or an IP. | `xbans.ipban` |
| `/tempipban <player\|ip> <duration> [reason]` (`/tempbanip`) | An IP ban for a while. | `xbans.ipban` |
| `/mute <player> [duration] [reason]` | Mutes: no chat, no blocked commands, signs or books. | `xbans.mute` |
| `/tempmute <player> <duration> [reason]` | Mutes for a while. | `xbans.tempmute` |
| `/ipmute <player\|ip> [duration] [reason]` (`/muteip`) | Mutes everyone on the player's IP. | `xbans.ipmute` |
| `/tempipmute <player\|ip> <duration> [reason]` (`/tempmuteip`) | An IP mute for a while. | `xbans.ipmute` |
| `/warn <player> [duration] [reason]` | Warns. A warning lasts 30 days unless given a duration (`warnings.default-duration`). | `xbans.warn` |
| `/kick <player> [reason]` | Kicks a player who's online, and records it. | `xbans.kick` |
| `/unban <player\|ip\|#id> [reason]` (`/pardon`, `/unbanip`, `/pardon-ip`) | Lifts a ban. Without `server:`, every ban the player has, on any server. | `xbans.unban` |
| `/unmute <player\|ip\|#id> [reason]` (`/unmuteip`) | Lifts a mute. | `xbans.unmute` |
| `/unwarn <player\|#id> [reason]` | Removes the player's newest warning, or the one given. | `xbans.unwarn` |

A player can be named by name or uuid, online or not. A player who has never joined can be banned too, before they arrive.

`#id` is a punishment's number, shown in `/history` and on the ban screen.

### Flags

Flags can go anywhere after the player's name. Everything after `--` is the reason, even words that look like flags.

| Flag | Means | Permission |
| --- | --- | --- |
| `-s` | Silent: only staff with `xbans.notify.silent` are told. | |
| `-S` | Extra silent: only the console is told. | `xbans.extrasilent` |
| `-p` | Public, when `punishing.silent-by-default` is on. | |
| `-g` | Every server: the same as `server:*`. | `xbans.scope` |
| `-I` | By IP as well, as `/ipban` and `/ipmute` do. | `xbans.ipban` or `xbans.ipmute` |
| `server:<name>` | Only on that server (see [Networks](/xbans/networks/)). `server:*`, `server:global` or `server:all` for every server, `server:local` or `server:this` for this one. | `xbans.scope` |

```
/ban Bob -s 10d server:survival Cheating
```

### Durations

A number and a unit, units added up in any order: `30m`, `1d2h`, `1.5h`, `2w`, `5 days`.

| Unit | Means |
| --- | --- |
| `s` | seconds |
| `m` | minutes |
| `h` | hours |
| `d` | days |
| `w` | weeks |
| `mo` | months (30 days) |
| `y` | years (365 days) |

`perm`, `permanent` or `forever` is permanent. Without a duration, `/ban` and `/mute` are permanent.

### Templates

`/ban Steve hacking` follows the `hacking` template: its reason, and a duration that climbs each time the same player gets it, 7 days, then 30, then permanent. A duration typed with a template (`/ban Steve 3d hacking`) is used instead of the ladder's. Templates are set in the [configuration](/xbans/configuration/#templates). Tab completion lists the ones you may use.

### Replacing a punishment

A new ban or mute on someone already banned or muted on the same server replaces the old one if it lasts at least as long; a shorter one is refused, so a helper can't turn a permanent ban into a day. Unban first to shorten one.

## Looking things up

| Command | What it does | Permission |
| --- | --- | --- |
| `/history <player> [bans\|mutes\|warnings\|kicks] [page]` (`/hist`) | Everything a player was given, newest first. Click the footer for more. | `xbans.history` |
| `/staffhistory <staff\|console> [type] [page]` (`/staffhist`) | Everything a staff member gave. | `xbans.staffhistory` |
| `/checkban <player\|ip\|#id>` | Whether they're banned, why, by whom and until when. | `xbans.checkban` |
| `/checkmute <player\|ip\|#id>` | The same for mutes. | `xbans.checkmute` |
| `/warnings [player] [page]` | Active warnings: your own, or with `xbans.warnings.others`, anyone's. | `xbans.warnings` (everyone) |
| `/banlist [page]` | Everyone banned on this server. | `xbans.banlist` |
| `/mutelist [page]` | Everyone muted on this server. | `xbans.mutelist` |
| `/alts <player\|ip> [page]` (`/dupeip`, `/checkalts`) | Accounts that joined from the same IPs, and whether each is banned, muted or online. | `xbans.alts` |
| `/iphistory <player> [page]` | The IPs a player joined from, with when. | `xbans.iphistory` |
| `/prunehistory <player> [duration] [confirm]` | Deletes a player's punishments that are no longer in force. With a duration, only those made within it. Shows how many first; add `confirm` to delete. | `xbans.prunehistory` |

## Admin

| Command | What it does | Permission |
| --- | --- | --- |
| `/xbans import <vanilla\|essentials\|litebans> [confirm]` | Counts what a source holds, then imports it with `confirm`. See [Switching](/xbans/switching/). | `xbans.admin` |
| `/xbans reload`, `/xbans info` | Reload the config, or show the version and settings. | `xbans.admin` |

## Staff limits

| Permission | What it does | Default |
| --- | --- | --- |
| `xbans.weight.<n>` | A rank's weight. A player with a weight can only be punished by someone with a higher one. Players without one can be punished by any staff. | nobody |
| `xbans.exempt` | Can't be punished by staff at all (the console still can). | nobody |
| `xbans.limit.<group>` | The group's longest ban and mute, from `staff-limits.groups`. | nobody |

Operators don't get these by default, so an operator isn't held to a helper's limit, and isn't exempt. A player's weight and exemption are recorded when they join, so they count while they're offline too.

```
/lp group helper permission set xbans.weight.10 true
/lp group helper permission set xbans.limit.helper true
/lp group moderator permission set xbans.weight.50 true
```

## Who's told

| Permission | Sees | Default |
| --- | --- | --- |
| `xbans.notify.broadcast` | Public punishments. | everyone |
| `xbans.notify.silent` | Silent (`-s`) punishments, with `[Silent]` in front. | op |
| `xbans.notify.banned-join` | A banned player trying to join. | op |
| `xbans.notify.alts` | A player joining who shares an IP with a banned account. | op |
| `xbans.scope` | May choose the server with `server:` or `-g`. | op |
