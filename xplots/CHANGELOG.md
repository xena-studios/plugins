# xPlots changelog

## Unreleased

### Fixed

- `/xplots reload` from the console of an empty server now runs while Paper has paused it, instead of waiting for a player to join.
- After `/xplots plot create`, the reply said the plot's sign is in the middle of its south edge. It's placed as the field's signs are, which is the left corner by default; the reply says so now.
- While Modrinth is still reviewing xPlots, the update checker asks GitHub for its releases instead, so you hear about new versions either way. It sends GitHub only xPlots' version, as it does Modrinth.

## 1.0.1

Replace the jar and restart. Your config gains two sign settings; everything else is kept.

### Added

- **Corner signs.** `signs.position` puts each plot's sign at its `left-corner` (the new default), `right-corner` or `middle`, as seen from the walkway, and `signs.rotation` turns corner signs `diagonal`, out from their corner, so they read from both walkways where they cross. A field can have its own with `/xplots field set <field> sign-position` and `sign-rotation`, which move its signs at once. Fields made with 1.0.0 keep their signs where they are until you run `/xplots field signs <field>`.
- Sneak and right click a plot's sign with the wand to turn it 45°. Moving a sign with the wand faces it toward you to the nearest 22.5°, not just the four sides.

### Fixed

- The plot menu's pay buttons showed the plot's rent and time left instead of what paying 1, 2 or 3 periods costs and adds, and "Buy a member slot" showed the rent instead of the slot price. What players were charged was right; the "Paid" message showed the same wrong amount.
- The field preview's outline was the same green as grass, so it was hard to see on a grass field. It's white now, and twice as dense.

## 1.0.0

The first release. Drop the jar in and start the server: plots are kept in SQLite in its own folder, with nothing to set up. Run `/xplots wand`, select the plot area, and `/xplots field create box size:5 path:2`.

### Setting up

- A whole field of plots from one selection: any plot size (`size:5`, `size:5x7`) and path width, centred in the selection and previewed in particles before `/xplots confirm`.
- Every plot's sign is placed for you, on the path facing its plot. Move one with the wand.
- `/xplots plot create` for a single plot that doesn't fit the grid.
- Fields share settings: price, rent period, prepaid limit, member slots, slot price, reset and abandon refund. Each can be changed per field with `/xplots field set`.

### Renting

- Click a free plot's sign to rent it, after a menu that shows what it costs.
- `/plots` and the plot menu: pay up to three periods ahead (to a week by default), add and remove members, buy member slots, teleport home, abandon.
- Rent through Vault, or with an item matched by material, name and custom model data.
- Warnings a day and an hour before rent runs out, or when the owner next joins.
- `xplots.limit.<n>` lets a player rent more than one plot.

### Protection and reset

- Only a plot's owner and members build, open containers and use doors there. Paths, signs and free plots are staff's alone; `xplots.bypass` builds anywhere.
- Pistons, liquids, fire, explosions, mobs and growing trees can't cross into or between plots.
- When rent runs out or a plot is abandoned, it's put back as it was made, a few milliseconds per tick.
- With WorldGuard installed, one region per field allows building, so a world-wide `build deny` doesn't block plots.

### Also

- PlaceholderAPI: `%xplots_owned%`, `%xplots_time_left%`, `%xplots_plot%`, `%xplots_plot_owner%`.
- Every message, sign line and menu button is in the config.
