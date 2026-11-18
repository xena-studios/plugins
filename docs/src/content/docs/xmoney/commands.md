---
title: Commands
description: xMoney's commands for players and staff, its permissions and placeholders.
sidebar:
  order: 3
---

Every command can be turned off, given other aliases or another permission in `commands` in the config, and applies on `/xmoney reload`. Commands take players who are offline, as long as they've played here.

## Players

Everyone can use these, unless you set a permission for them.

| Command | What it does |
| --- | --- |
| `/balance [player]` (`/bal`, `/money`) | Your balance. Someone else's needs `xmoney.balance.others`. |
| `/pay <player> <amount>` | Sends money to another player. Payments of `confirm-above` or more ask first, with a button. |
| `/baltop [page]` (`/balancetop`, `/moneytop`) | The richest players, and all the money there is. |

Amounts can be typed `250`, `1,000`, `12.5`, `1.5k` or `2m`. More decimals than the currency has are refused rather than rounded.

## Staff

| Command | What it does |
| --- | --- |
| `/eco give <player> <amount>` (`/economy`) | Adds to a balance. |
| `/eco take <player> <amount>` | Takes from a balance, if they have it. |
| `/eco set <player> <amount>` | Sets a balance. |
| `/eco reset <player>` | Sets a balance back to `starting-balance`. |
| `/eco history <player> [page]` | Every change to a balance, newest first: when, how much, why, and the balance after. |
| `/xmoney import <essentials\|cmi\|vault> [confirm [overwrite]]` | Imports balances from another plugin. Without `confirm`, says what it would do. `overwrite` is needed too if some of those accounts changed in xMoney since. See [Switching](/xmoney/switching/). |
| `/xmoney status` | Where balances are kept, and whether saving is keeping up. |
| `/xmoney reload`, `/xmoney info` | Reload the config, or show the version and settings. |

`/eco` also works on accounts other plugins made by name, such as a town's bank: `/eco history town-Spawn`.

## Permissions

| Permission | Default | What it allows |
| --- | --- | --- |
| `xmoney.balance.others` | Operators | Another player's balance. |
| `xmoney.eco` | Operators | `/eco`. Each part also needs its own: |
| `xmoney.eco.give`, `.take`, `.set`, `.reset`, `.history` | Operators | That part of `/eco`. |
| `xmoney.import` | Operators | `/xmoney import`. |
| `xmoney.status` | Operators | `/xmoney status`. |
| `xmoney.admin` | Operators | `/xmoney reload` and `/xmoney info`. |

## PlaceholderAPI

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed:

| Placeholder | Shows |
| --- | --- |
| `%xmoney_balance%` | The player's balance, formatted: `$1,234.50`. |
| `%xmoney_balance_raw%` | Just the number: `1234.50`. |
| `%xmoney_balance_short%` | Short: `1.2k`. |
| `%xmoney_top_<rank>_name%` | Who's at that place in `/baltop`, from 1 to 100. |
| `%xmoney_top_<rank>_balance%` | Their balance, formatted. Also `_balance_raw` and `_balance_short`. |

They're answered from memory, so scoreboards can ask as often as they like.
