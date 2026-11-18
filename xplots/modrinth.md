![xPlots by Xena Studios. A lime pixel signpost icon and the xPlots name over a stepped horizon in lime, with the headline "Rentable plots, set up in a minute."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xplots/banner.png)

## Rentable plots, set up in a minute.

Small plots, close together, that players rent with in-game money: the box-server staple. Usually that means a WorldGuard region and a sign for every plot, made by hand. **With xPlots you select the whole area, say how big each plot is, and confirm. Every plot and every sign is made for you.**

**[Read the docs](https://docs.xenastudios.co/xplots/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Setup in a minute, not an afternoon.** One selection becomes a grid of plots with paths between them. You see it outlined in particles before anything changes, then the signs go up on their own.
- **You won't feel it running.** Finding which plot a block is in is a bit of arithmetic, the same for ten plots or ten thousand. There's one timer, set for the next rent to run out, and signs refresh once a minute, only where players are. Nothing else repeats.
- **Plots come back clean.** Each plot is saved when it's made. When its rent runs out or it's abandoned, it's put back exactly as it was, a few milliseconds per tick.
- **No region per plot.** xPlots protects its own plots. If WorldGuard is installed, it makes one region per field so a world-wide `build deny` doesn't block your plots, and that's all.
- **Vault or your own coin.** Rent goes through your economy, or it can take an item instead: match it by material, name and custom model data, so a box server's Coin works as it is.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

---

## What players get

- **Click a free plot's sign to rent it.** A menu shows the price, how long it lasts and how many members it holds first, so nothing gets rented by accident.
- **`/plots`, or click your own sign,** to pay rent ahead (up to a week by default, so a plot is never lost to a missed window), add or remove members from a menu of player heads, buy more member slots, teleport home or abandon the plot.
- **Members** can build, open chests and use doors in the plot. The owner doesn't take up a member slot.
- **Warnings** a day and an hour before the rent runs out, sent when they next join if they were offline.
- **Signs that keep up:** who rents it, how long is left and how many members it has, changing colour when time is short.

---

## What your staff get

- `/xplots wand` and `/xplots field create`: a whole field of plots from one selection, with any plot size (`size:5` or `size:5x7`) and path width.
- **Fields** are groups of plots that share a price, rent period, member slots and reset setting. A VIP field is just another field with a higher price.
- `/xplots plot create` for a single plot that doesn't fit the grid, and sneak-click with the wand to move a sign or open a plot's staff menu.
- `/xplots plot evict`, `reset`, `setowner`, `addmember` and `removemember`, all tab-completed with plot names like `box-12`.
- `xplots.bypass` to build anywhere, and `xplots.limit.<n>` to let a rank rent more than one plot at a time.
- **PlaceholderAPI:** `%xplots_owned%`, `%xplots_time_left%`, `%xplots_plot%`, `%xplots_plot_owner%`.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. Plots are kept in SQLite in its own folder, with nothing to set up.
2. Run `/xplots wand` and click two opposite corners of the plot area. The height of your selection is how high players can build.
3. Run `/xplots field create box size:5 path:2`, check the outline, and `/xplots confirm`.

Prices, rent periods, member slots and every message, sign and menu are in `plugins/xPlots/config.yml`, and apply on `/xplots reload`.

Full setup, every setting and every permission: **[docs.xenastudios.co/xplots](https://docs.xenastudios.co/xplots/)**

**Requires** Paper 1.21.11 or newer (or Purpur) and Java 21 or newer. Vault and an economy plugin to pay rent with money (not needed for item currency). WorldGuard and PlaceholderAPI are optional. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xplots)
