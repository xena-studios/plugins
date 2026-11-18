# xPlots changelog

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
