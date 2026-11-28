---
title: Commands
description: xPlots' commands for players and staff, and its permissions.
sidebar:
  order: 3
---

## Players

Everyone has `xplots.use`, which `/plots` and renting need.

| Command | What it does |
| --- | --- |
| `/plots` (`/plot`) | Your plot's menu: pay rent, members, slots, teleport, abandon. With more than one plot, a list of them first. |
| `/plots add <player>` | Adds a member to your plot: the one you're standing in, or your only one. Works for players who are offline but have joined before. |
| `/plots remove <player>` | Removes a member. |
| `/plots home [plot]` | Teleports to your plot, after the warmup. |
| `/plots warp [field]` | Teleports to a field's warp. |

Clicking a free plot's sign opens the rent menu; clicking your own sign opens your plot's menu; clicking someone else's says who rents it, for how long, and its members.

## Staff

Every `/xplots` command needs `xplots.admin`, which operators have.

| Command | What it does |
| --- | --- |
| `/xplots wand` | The selection wand. Left click: first corner. Right click: second corner. Sneak and right click a block: move the nearest plot's sign there. Sneak and right click a sign: turn it 45°. Sneak and left click: a plot's staff menu. |
| `/xplots select <world> <x1> <y1> <z1> <x2> <y2> <z2>` | Selects by typing the corners, as from the console. |
| `/xplots field create <name> size:<n or WxD> [path:<n>]` | Splits the selection into a field of plots and previews it. |
| `/xplots confirm`, `/xplots cancel` | Makes or drops what's waiting: a previewed field, or deleting one. |
| `/xplots field delete <field>` | Deletes a field and its plots, after you confirm. |
| `/xplots field list` | Every field, and how many of its plots are rented. |
| `/xplots field info <field>` | A field's size and its settings. |
| `/xplots field set <field> <setting> <value or default>` | Changes one of a field's settings: see [Setting up](/xplots/v1/setting-up/#prices-and-settings). |
| `/xplots field signs <field>` | Moves every sign in the field to where its sign settings say, undoing any moved by hand. |
| `/xplots field setwarp <field> [remove]` | Sets the field's warp where you stand, or removes it. |
| `/xplots plot create <field>` | Makes one plot from the selection, in the field. |
| `/xplots plot delete [plot]` | Deletes a plot. |
| `/xplots plot info [plot]` | A plot's size, owner, time left and members. |
| `/xplots plot evict [plot]` | Ends its rent now. |
| `/xplots plot reset [plot]` | Puts it back as it was made. The renter keeps it. |
| `/xplots plot setowner <plot> <player>` | Gives a plot to a player: a free one for one period at no cost, a rented one with its time and members. |
| `/xplots plot addmember <plot> <player>`, `removemember` | Adds or removes a member, ignoring slots. |
| `/xplots reload`, `/xplots info` | Reload the config, or show the version and settings. |

Plots are named after their field: `box-12`. Without a plot name, the plot commands use the plot you're standing in.

## Permissions

| Permission | Default | What it allows |
| --- | --- | --- |
| `xplots.use` | Everyone | Renting plots, and `/plots`. |
| `xplots.limit.<n>` | Nobody | Renting up to `<n>` plots at once, instead of `max-plots`. The highest one a player has counts. |
| `xplots.warmup.bypass` | Operators | Teleporting without the warmup. |
| `xplots.bypass` | Operators | Building and opening anything in any field. |
| `xplots.admin` | Operators | Every `/xplots` command. |

## PlaceholderAPI

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed:

| Placeholder | Shows |
| --- | --- |
| `%xplots_owned%` | How many plots the player rents. |
| `%xplots_time_left%` | Time left on the player's plot that runs out soonest. |
| `%xplots_plot%` | The plot the player is standing in. |
| `%xplots_plot_owner%` | Who rents the plot the player is standing in. |
