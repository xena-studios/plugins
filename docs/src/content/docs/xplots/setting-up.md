---
title: Setting up
description: Make a field of rentable plots in xPlots, set its prices, and fix up signs and odd plots.
sidebar:
  order: 2
---

## Your first field

1. **Get the wand:** `/xplots wand`. Left click one corner of the whole plot area, and right click the opposite corner. The selection's height is how high players can build: select from the floor up to where building should stop.
2. **Split it into plots:** `/xplots field create box size:5 path:2`. `box` is the field's name; plots are named after it, `box-1`, `box-2` and so on. `size:5` makes 5×5 plots and `size:5x7` makes plots 5 wide and 7 deep. `path:2` leaves 2 blocks between plots (1 if you leave it out).
3. **Check the outline.** Every plot's edge is drawn in particles around you, with a mark where each sign will go, and chat says how many fit: *"6 × 4 = 24 plots of 5×5"*.
4. **Confirm:** `/xplots confirm`, or `/xplots cancel` to start over. Each plot's blocks are saved, then every sign is placed.

The grid is centred in your selection, and whatever is left over at the edges becomes path. Rows face each other in pairs across a path, and each plot's sign stands on that path, at the front edge's left corner as you look at the plot. A path narrower than 2 blocks has no room for two rows of signs, so then each sign stands just inside its plot.

## Where signs go

| Setting | Values |
| --- | --- |
| `signs.position` | `left-corner` (the default), `right-corner` or `middle` of the plot's front edge, as seen from the walkway |
| `signs.rotation` | `straight` faces the walkway; `diagonal` turns a corner sign 45° out from its corner, so it reads from both walkways where they cross |

A field can have its own: `/xplots field set box sign-position middle`, or `sign-rotation diagonal`. Its signs move as soon as you set it. After changing the config's, run `/xplots field signs box` to move a field's signs to match; that also undoes signs moved by hand.

From the console, select by typing the corners: `/xplots select world 0 64 0 40 70 30`. With WorldEdit installed, `/xplots select` on its own takes your WorldEdit selection.

## What's protected

The field's whole footprint is protected, at every height. Inside a plot, between the selection's lowest and highest blocks, its owner and members can build. Everywhere else in the field (the paths, above and below the plots, the signs, and plots nobody rents) belongs to staff with `xplots.bypass`.

On paths, anyone can use doors, buttons and levers you put there, but not containers. Pistons, flowing liquids, fire, growing trees and dispensers can't reach from one plot into another, or into the field from outside. Explosions don't break anything in a field.

PvP isn't touched: keep using whatever you control it with.

## Prices and settings

Every field starts with the settings under `plots` in the [config](/xplots/configuration/). Change one for a single field:

```
/xplots field set vip price 500
/xplots field set vip period 7d
/xplots field set vip max-slots 6
/xplots field set vip price default
```

`default` goes back to the config's value. `/xplots field info vip` shows every setting, and which are the field's own.

| Setting | What it is |
| --- | --- |
| `price` | What one period of rent costs. |
| `period` | How long one payment lasts: `3d`, `12h`, `1d12h`. |
| `max-prepaid` | The most rent that can be paid ahead. |
| `starting-slots` | Members a new rental can add. The owner doesn't take a slot. |
| `max-slots` | The most member slots a plot can have after buying more. |
| `slot-price` | What one more member slot costs. |
| `reset` | Whether a plot is put back as it was made when its rent ends. |
| `abandon-refund` | How much of the unused rent abandoning gives back, in percent. |
| `sign-position` | `left-corner`, `right-corner` or `middle`: see [Where signs go](#where-signs-go). |
| `sign-rotation` | `straight` or `diagonal`. |
| `auto-renew` | 2.0: whether rent renews on its own from the owner's balance an hour before it runs out. See [Renewals](#renewals). |

## A warp

Stand where players should arrive and run `/xplots field setwarp box`. Players then use `/plots warp box`, or just `/plots warp` if only one field has a warp. Teleports wait 3 seconds, cancelled if the player moves (`teleport-warmup`, skipped with `xplots.warmup.bypass`).

## Renewals

With `auto-renew` on (the config's `plots.auto-renew`, or one field's), a rented plot renews for one more period an hour before it runs out, charged from its owner's balance through the economy, whether they're online or not; they're told then, or when they next join. An owner turns it off for their plot in its menu. A balance that doesn't cover it is tried once: the owner is told, and the plot runs out as it would have. Item currency can't renew (the item is in an inventory, not a balance).

Each renewal is charged once at most, even if the server stops in the middle of one: the charge is written down before the money is taken and cleared in the same write that extends the rent, and a start that finds one left asks xMoney's ledger whether it went through. With Vault, which keeps no ledger to ask, it's taken as paid.

## Rolling back griefing

With [xRewind](/xrewind/) 2.0 installed, an owner undoes what a player did in their plot, and only in their plot: `/plots rollback Steve` (the last day) or `/plots rollback Steve 2h`. Staff do the same for any plot with `/xplots plot rollback box-12 2h Steve`.

## Fixing things up

- **Move a sign:** sneak and right click a block with the wand. The nearest plot's sign moves onto that block, facing you.
- **Turn a sign:** sneak and right click the sign itself with the wand. Each click turns it 45°.
- **A plot that doesn't fit the grid:** select it with the wand and run `/xplots plot create box`. It joins the field, with its settings, as the field's next number. Its sign goes outside its south edge, placed as the field's signs are; move it if that's not right.
- **A plot's staff menu:** sneak and left click inside the plot with the wand, for its owner and members, teleport, evict and reset.
- **Remove a plot or a field:** `/xplots plot delete box-12`, or `/xplots field delete box` (which asks you to confirm). Rentals end with no refund and signs come down; the blocks stay as they are.

## When plots reset

Each plot's blocks are saved when it's made. When its rent runs out, or it's abandoned or evicted, it's put back as it was saved, a few milliseconds per tick (`reset-ms-per-tick`). Players standing in it are moved to the path, and dropped items, item frames, armour stands, minecarts and boats in it are removed; mobs and pets aren't. Nobody can build in it or rent it until it's done.

`/xplots plot reset box-12` puts a plot back without ending its rent. Set `reset` to `false` for a field whose plots should keep what was built.

If you rebuild a field's plots by hand after making it, delete and make the field again, so the saved blocks match.
