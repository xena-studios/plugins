---
title: Commands
description: xVote's commands for players and staff, its permissions and placeholders.
sidebar:
  order: 4
---

## Players

Everyone has `xvote.use`.

| Command | What it does |
| --- | --- |
| `/vote` | Every site, with a link to vote and when each opens again, your total and streak, and the vote party's progress. |
| `/vote top [day\|week\|month\|all] [page]` | The top voters. This month's when no period is given. |
| `/vote claim` | Reward items that didn't fit in your inventory. |

## Staff

| Command | Permission | What it does |
| --- | --- | --- |
| `/vote test <player> [site]` | `xvote.test` | Sends a signed test vote through the receiver's own port, end to end. `site` is a key from `sites`, or any service name. It counts like a real vote. |
| `/xvote status` | `xvote.admin` | The receiver, the database, votes waiting, and the last votes with the service name each list sends. |
| `/xvote key` | `xvote.admin` | The public key for lists that use Votifier v1. |
| `/xvote token [service]` | `xvote.admin` | The tokens for Votifier v2, or one list's, made if it has none. |
| `/xvote import <nuvotifier\|votingplugin\|superbvote>` | `xvote.admin` | Takes over NuVotifier's keys and tokens, or adds another plugin's totals: see [Switching](/xvote/switching/). |
| `/xvote party [start]` | `xvote.admin` | The vote party's progress, or starts one now. |
| `/xvote reload`, `/xvote info` | `xvote.admin` | Reload the config, or show the version and settings. |

## Permissions

| Permission | Default | What it allows |
| --- | --- | --- |
| `xvote.use` | Everyone | `/vote`, `/vote top` and `/vote claim`. |
| `xvote.test` | Operators | `/vote test`. |
| `xvote.admin` | Operators | Every `/xvote` command. |

## PlaceholderAPI

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed, all answered from memory:

| Placeholder | Shows |
| --- | --- |
| `%xvote_total%`, `%xvote_today%`, `%xvote_week%`, `%xvote_month%` | The player's votes. |
| `%xvote_streak%`, `%xvote_best_streak%` | Days in a row they've voted, and their longest run. |
| `%xvote_available%` | Sites they can vote on now. |
| `%xvote_party_votes%`, `%xvote_party_needed%`, `%xvote_party_left%` | The vote party's progress. |
| `%xvote_top_<period>_<n>_name%`, `%xvote_top_<period>_<n>_votes%` | The leaderboards: `<period>` is `day`, `week`, `month` or `all`, and `<n>` the place, from 1. |
