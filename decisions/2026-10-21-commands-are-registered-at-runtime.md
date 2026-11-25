# Commands are registered at runtime, so command changes apply on reload (2026-10-21)

Spike (`sandbox/`, checked in CI): a command registered through the Bukkit command map after startup runs by name, alias and `namespace:name`, tab-completes, and appears in the command tree the server sends clients. Removing it from `getKnownCommands()` takes it out of that tree, and re-registering with new aliases drops the old ones. Each takes well under 10 ms. This passes on Paper and Folia, 1.21.11 and 26.3.

So turning a command on or off, its aliases and permission, and adding or removing link commands can all apply on `/<plugin> reload` (followed by `Player#updateCommands`), instead of needing a restart. A disabled command isn't registered at all, so another plugin can take its name. Paper's Brigadier lifecycle API can't do this: it only registers at startup and on a datapack reload.

Still to confirm with a real client: that `updateCommands` refreshes tab completion for players already online. (It's the call LuckPerms and others make for the same reason.)

Gotcha: a command dispatched while another command is running is queued until that one finishes, so code that dispatches commands must run outside a command's execution.
