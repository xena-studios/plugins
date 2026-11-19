---
title: xPlots
description: "xPlots: rentable plots for Paper box and PvP servers: select an area and it becomes a grid of plots with signs, rented with Vault money or an item."
sidebar:
  label: Overview
  order: 1
---

xPlots rents small plots to players for in-game money, the box-server staple, on Paper and Purpur. Instead of a region and a sign for every plot made by hand, you select the whole area, say how big each plot is, and confirm: every plot and every sign is made for you.

- **Setup in a minute.** One selection becomes a grid of plots with paths between them, outlined in particles before anything changes. See [Setting up](/xplots/setting-up/).
- **You won't feel it running.** Finding which plot a block is in is a bit of arithmetic, the same for ten plots or ten thousand, and a block outside every field stops at the first check. There's one timer, set for the next rent to run out, and signs are checked once a minute, only in loaded chunks. Nothing else repeats, and protection isn't listened for until you make a field.
- **Plots come back clean.** Each plot is saved when it's made, and put back as it was when its rent runs out or it's abandoned, a few milliseconds per tick.
- **No region per plot.** xPlots protects its own plots. With WorldGuard installed, it makes one region per field, so a world-wide `build deny` doesn't block them.
- **Vault or your own coin.** Rent goes through your economy, or takes an item instead, so a box server's Coin works as it is.

## How it works

A **field** is a group of plots that share settings: price, rent period, member slots and whether plots reset. Make one from a selection, and it's split into a grid of plots with paths between them. A pricier VIP field is just another field.

Players **click a free plot's sign** to rent it for one period. Then `/plots`, or clicking their own sign, opens the plot menu: pay rent ahead, add or remove members, buy more member slots, teleport home, or abandon the plot.

The **owner and members** can build, open containers and use doors and buttons in the plot. Only the owner manages it, and the owner doesn't take up a member slot. Nobody else can change anything in it, and the paths, signs and free plots belong to staff.

When the **rent runs out**, the owner is warned a day and an hour before (or when they next join), then the plot is freed, its members removed, and it's put back as it was made. Anything built in it is lost, items in chests included.

## Storage

Plots are kept in SQLite out of the box, in `plugins/xPlots/plots.db`, or in MySQL/MariaDB. Everything is held in memory while the server runs and saved in the background, so a click never waits on the disk. See [Configuration](/xplots/configuration/).

## What else it does

- **Every feature free.** No premium version, no early-access builds, nothing behind a Patreon.
- **Every change applies on reload.** Prices, rent periods, signs and menus, with `/xplots reload`. Only a change of storage waits for a restart.

## Getting started

1. [Install it](/start/installing/), and if rent is paid in money, Vault and an economy plugin.
2. Make your first field: [Setting up](/xplots/setting-up/).
3. Set prices, rent periods and slots: [Configuration](/xplots/configuration/).
4. Every command and permission: [Commands](/xplots/commands/).
