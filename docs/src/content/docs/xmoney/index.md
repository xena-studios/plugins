---
title: xMoney
description: "xMoney: an economy plugin with Vault built in for Paper and Folia servers, with balances, /pay, /baltop and a ledger of every change, so shop and jobs plugins work without Vault.jar."
sidebar:
  label: Overview
  order: 1
---

xMoney is an economy plugin for Paper and Folia with Vault built in. A shop, jobs or vote plugin that pays money usually needs Vault and an economy plugin behind it; xMoney is both, so plugins written for Vault find it, and the money, without Vault installed.

- **One jar instead of two.** xMoney carries Vault's API and VaultUnlocked's, and answers both. Plugins that `depend` on Vault load and work as they always have, on Paper and Folia.
- **No money goes missing unexplained.** Every change to every balance is a line in the ledger: who, how much, the balance after, and why ("ChestShop", "paid Alex", "given by Steve"). `/eco history` shows it.
- **Amounts that add up.** Money is counted in whole numbers, not floating point, so 0.1 + 0.2 is exactly 0.30, and badly behaved plugins can't make or lose money with odd amounts.
- **Networks (experimental).** Servers sharing one MySQL or MariaDB database share balances, and can never spend the same money twice. See [Networks](/xmoney/configuration/#networks) for what's experimental.
- **Easy to switch to.** One command imports from EssentialsX, CMI, or whatever economy plugin is registered with Vault now, with a dry run first. See [Switching](/xmoney/switching/).

## It replaces Vault

xMoney tells the server it provides Vault, so don't install Vault or VaultUnlocked beside it. If one is still in `plugins/`, Paper loads xMoney first and then refuses the other jar, with an error of its own that looks like this:

```
[ModernPluginLoadingStrategy] Could not load plugin 'VaultUnlocked-2.20.1.jar' in folder 'plugins'
```

Nothing breaks: every plugin keeps using xMoney. xMoney then names the jar in a warning. Delete it and the error goes away.

Vault also carries two other APIs, for permissions and chat prefixes. They're in xMoney too, so permissions plugins such as LuckPerms hook them as before. On a server with no permissions plugin, xMoney answers Vault's permission questions from the server's own permissions, as Vault did.

## Commands

`/balance`, `/pay`, `/baltop`, and `/eco` for staff. See [Commands](/xmoney/commands/).

## Storage

Balances and the ledger are kept in SQLite out of the box, in `plugins/xMoney/money.db`, or in MySQL/MariaDB. Ledger lines older than a year are deleted once a day; balances never depend on them. See [Configuration](/xmoney/configuration/).

## What else it does

- **Every feature free.** No early-access builds, nothing behind a Patreon.
- **You won't feel it running.** Every balance is in memory, so a shop asking about a player, online or not, never waits on the disk. Changes are saved by one background thread as they happen, with no timer. The only listener is for players joining.
- **Every change applies on reload**, except `storage` and `network`, which take a restart.

## Getting started

1. [Install it](/start/installing/), and remove Vault or VaultUnlocked if you have one.
2. Set your currency's name, symbol and decimals: [Configuration](/xmoney/configuration/).
3. Give your staff the permissions they need: [Commands](/xmoney/commands/).
4. Coming from EssentialsX, CMI or another economy plugin? Bring the balances: [Switching](/xmoney/switching/).

## Licences

xMoney is [GPL-3.0](https://github.com/xena-studios/plugins/blob/main/LICENSE). To stand in for Vault, its jar includes the classes of [VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI) 2.20 (which includes [Vault's API](https://github.com/MilkBowl/VaultAPI)), unmodified, under the [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html). The licence texts and a notice are in the jar, under `META-INF/licenses/`.
