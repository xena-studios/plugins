---
title: Configuration
description: Every setting in xUtilities' config.yml.
sidebar:
  order: 4
---

The config is `plugins/xUtilities/config.yml`, written on first start. Apply changes with `/xutilities reload`; [some need a restart](/start/installing/#reload-or-restart).

## Every command has the same four settings

```yaml
commands:
  fly:
    enabled: true                                   # false removes the command (restart)
    aliases: ["flight", "togglefly", "toggleflymode"] # restart
    permission: "xutilities.command.fly"            # blank = anyone (restart)
    permission-others: "xutilities.command.fly.others"
    # ...then the command's messages
```

`permission-others` exists only on commands with a `[player]` form. All text is [MiniMessage](/start/formatting/).

## Command options

Beyond messages, a few commands have their own settings:

| Setting | Default | What it does |
| --- | --- | --- |
| `speed.min`, `speed.max` | `1`, `10` | The range `/speed` accepts, on a 1 to 10 scale (Minecraft's 0.1 to 1.0). |
| `heal.restore-hunger` | `true` | `/heal` also restores hunger. |
| `heal.extinguish-fire` | `true` | `/heal` also puts out fire. |
| `heal.clear-negative-effects` | `true` | `/heal` also removes harmful potion effects. |
| `clear.include-armor` | `true` | `/clear` also empties armour and off-hand; `false` clears only the main 36 slots. |
| `trash.title` | `Disposal` | The `/trash` menu's title. |
| `<workstation>.message` | blank | Sent when a workstation menu opens. Blank sends nothing. |

## Messages and placeholders

Each command's messages are under its own key (`message-self`, `message-others` and so on). These placeholders are available where they apply:

| Placeholder | In |
| --- | --- |
| `<player>` | any `-others` message: the other player's name |
| `<mode>` | gamemode messages |
| `<state>` | `/fly` messages: the word from `messages.state` (`enabled` / `disabled`) |
| `<speed>`, `<min>`, `<max>` | `/speed` messages |
| `<yaw>`, `<pitch>` | `/setrotation` messages |
| `<ping>` | `/ping` messages, in milliseconds |

Shared error messages (no permission, player not found, players only) are under `messages.error`.

## Link commands

`info-commands` holds `/discord`, `/store`, `/website` and any you add: see [Link commands](/xutilities/link-commands/).

## The full default config

```yaml title="plugins/xUtilities/config.yml"
# ============================================================================
#  xUtilities — lightweight, highly-configurable utility commands
# ----------------------------------------------------------------------------
#  Every command is individually toggleable, aliasable, permissioned, and has
#  fully customizable text. Bad values fall back to the documented default with
#  a warning in console — the plugin never disables itself or crashes over
#  config. All player-facing text is MiniMessage:
#  https://docs.advntr.dev/minimessage/format.html
#
#  Apply changes at runtime with:  /xutilities reload
#
#  RESTART-ONLY vs LIVE:
#   • Structural keys — a command's `enabled`, `aliases` and permissions, plus
#     which info-commands exist — are applied when commands are registered at
#     startup, so changing them needs a server RESTART.
#   • Everything else (all messages, speed bounds, heal options, clear options,
#     link contents, etc.) applies LIVE on `/xutilities reload`.
#
#  Permissions: leaving a permission blank ("") means NO restriction (anyone
#  may use it). Commands with a `[player]` target form use a separate
#  `permission-others` for acting on other players.
# ============================================================================

# Internal config version. DO NOT edit by hand — the plugin uses this to
# migrate older configs forward without wiping your settings.
config-version: 1

# ----------------------------------------------------------------------------
#  Shared messages
# ----------------------------------------------------------------------------
messages:
  error:
    # Sent when the console/command-block runs a self-only command.
    player-only: "<red>Only players can use this command.</red>"
    # Sent when a sender lacks the required permission.
    no-permission: "<red>You don't have permission to do that.</red>"
    # Sent when a `[player]` target can't be resolved.
    player-not-found: "<red>That player could not be found.</red>"
    # Sent when a disabled command is somehow invoked.
    disabled: "<red>This command is disabled.</red>"
  # Words substituted into <state> placeholders (e.g. flight toggles).
  state:
    enabled: "enabled"
    disabled: "disabled"

# ----------------------------------------------------------------------------
#  Commands
# ----------------------------------------------------------------------------
commands:

  # ---- gamemode ------------------------------------------------------------
  survival:
    enabled: true
    aliases: ["gms", "gm0"]
    permission: "xutilities.command.survival"
    permission-others: "xutilities.command.survival.others"
    message-self: "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>"
    message-others: "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>"
  creative:
    enabled: true
    aliases: ["gmc", "gm1"]
    permission: "xutilities.command.creative"
    permission-others: "xutilities.command.creative.others"
    message-self: "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>"
    message-others: "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>"
  adventure:
    enabled: true
    aliases: ["gma", "gm2"]
    permission: "xutilities.command.adventure"
    permission-others: "xutilities.command.adventure.others"
    message-self: "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>"
    message-others: "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>"
  spectator:
    enabled: true
    aliases: ["gmsp", "gm3"]
    permission: "xutilities.command.spectator"
    permission-others: "xutilities.command.spectator.others"
    message-self: "<gray>Your gamemode is now <aqua><mode></aqua>.</gray>"
    message-others: "<gray>Set <yellow><player></yellow>'s gamemode to <aqua><mode></aqua>.</gray>"
  # Parent command: /gamemode <survival|creative|adventure|spectator> [player].
  # Wires in whichever of the four mode commands above are enabled.
  gamemode:
    enabled: true
    aliases: ["gm"]
    permission: "xutilities.command.gamemode"
    permission-others: "xutilities.command.gamemode.others"

  # ---- player utility ------------------------------------------------------
  fly:
    enabled: true
    aliases: ["flight", "togglefly", "toggleflymode"]
    permission: "xutilities.command.fly"
    permission-others: "xutilities.command.fly.others"
    message-on-self: "<gray>Flight <green><state></green>.</gray>"
    message-off-self: "<gray>Flight <red><state></red>.</gray>"
    message-on-others: "<gray>Flight <green><state></green> for <yellow><player></yellow>.</gray>"
    message-off-others: "<gray>Flight <red><state></red> for <yellow><player></yellow>.</gray>"
  speed:
    enabled: true
    aliases: ["setspeed"]
    permission: "xutilities.command.speed"
    permission-others: "xutilities.command.speed.others"
    # Accepted input range on the 1-10 scale (mapped linearly to Minecraft's
    # 0.1-1.0 speed unit; applied to fly-speed while flying, walk-speed otherwise).
    min: 1
    max: 10
    message-self: "<gray>Speed set to <aqua><speed></aqua>.</gray>"
    message-others: "<gray>Set <yellow><player></yellow>'s speed to <aqua><speed></aqua>.</gray>"
    message-invalid: "<red>Speed must be between <min> and <max>.</red>"
  top:
    enabled: true
    aliases: []
    permission: "xutilities.command.top"
    message-success: "<gray>Teleported to the highest block.</gray>"
    message-unsafe: "<red>No safe block to teleport to here.</red>"
  setrotation:
    enabled: true
    aliases: []
    permission: "xutilities.command.setrotation"
    permission-others: "xutilities.command.setrotation.others"
    message-self: "<gray>Rotation set to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>"
    message-others: "<gray>Set <yellow><player></yellow>'s rotation to yaw <aqua><yaw></aqua>, pitch <aqua><pitch></aqua>.</gray>"
  ping:
    enabled: true
    aliases: []
    permission: "xutilities.command.ping"
    permission-others: "xutilities.command.ping.others"
    message-self: "<gray>Your ping is <aqua><ping></aqua>ms.</gray>"
    message-others: "<gray><yellow><player></yellow>'s ping is <aqua><ping></aqua>ms.</gray>"
  back:
    enabled: true
    aliases: []
    permission: "xutilities.command.back"
    message-success: "<gray>Teleported to your previous location.</gray>"
    message-none: "<red>You have no previous location to return to.</red>"

  # ---- player state & inventory --------------------------------------------
  heal:
    enabled: true
    aliases: []
    permission: "xutilities.command.heal"
    permission-others: "xutilities.command.heal.others"
    # Optional extras applied alongside restoring health:
    restore-hunger: true
    extinguish-fire: true
    clear-negative-effects: true
    message-self: "<green>You have been healed.</green>"
    message-others: "<green>Healed <yellow><player></yellow>.</green>"
  feed:
    enabled: true
    aliases: []
    permission: "xutilities.command.feed"
    permission-others: "xutilities.command.feed.others"
    message-self: "<green>You have been fed.</green>"
    message-others: "<green>Fed <yellow><player></yellow>.</green>"
  repair:
    enabled: true
    aliases: ["fix"]
    permission: "xutilities.command.repair"
    permission-others: "xutilities.command.repair.others"
    # Base form repairs the held item; `/repair all` repairs the whole inventory.
    message-self: "<green>Repaired your held item(s).</green>"
    message-others: "<green>Repaired <yellow><player></yellow>'s item(s).</green>"
    message-nothing: "<red>Nothing to repair.</red>"
  clear:
    enabled: true
    aliases: ["clearinventory", "ci"]
    permission: "xutilities.command.clear"
    permission-others: "xutilities.command.clear.others"
    # true also clears armor + off-hand; false clears only the main 36 slots.
    include-armor: true
    message-self: "<gray>Your inventory has been cleared.</gray>"
    message-others: "<gray>Cleared <yellow><player></yellow>'s inventory.</gray>"
  enderchest:
    enabled: true
    aliases: ["ec", "ender"]
    permission: "xutilities.command.enderchest"
    permission-others: "xutilities.command.enderchest.others"
    # Shown to the viewer when opening someone else's Ender Chest (blank = silent).
    message-others: "<gray>Opening <yellow><player></yellow>'s ender chest.</gray>"

  # ---- virtual workstation menus -------------------------------------------
  # Open the vanilla UI without needing the block placed. Optional `message`
  # is sent on open (blank = silent). Default permission is `op`.
  anvil:
    enabled: true
    aliases: []
    permission: "xutilities.command.anvil"
    message: ""
  craft:
    enabled: true
    aliases: ["workbench", "wb"]
    permission: "xutilities.command.craft"
    message: ""
  grinder:
    enabled: true
    aliases: ["grindstone"]
    permission: "xutilities.command.grinder"
    message: ""
  loom:
    enabled: true
    aliases: []
    permission: "xutilities.command.loom"
    message: ""
  smith:
    enabled: true
    aliases: ["smithingtable", "smithtable"]
    permission: "xutilities.command.smith"
    message: ""
  trash:
    enabled: true
    aliases: ["disposal"]
    permission: "xutilities.command.trash"
    # Inventory title (MiniMessage). Contents are discarded when the menu closes.
    title: "Disposal"
    message: ""

# ----------------------------------------------------------------------------
#  Info / link commands (fully custom, data-driven)
# ----------------------------------------------------------------------------
#  Each KEY below becomes a command name. Add, edit, rename or delete entries
#  freely — e.g. add a `vote:` block to get /vote with no code changes. Each
#  entry has `enabled`, `aliases`, an optional `permission` (defaults to
#  `xutilities.command.<key>`, registered as usable by everyone), and a
#  MiniMessage `message` (typically a clickable link). An entry whose name or
#  alias collides with a core command is skipped with a console warning.
#
#  Structural (adding/removing/renaming/aliases/permission) is RESTART-ONLY;
#  editing a `message` applies LIVE on reload. Replace the example URLs below!
# ----------------------------------------------------------------------------
info-commands:
  discord:
    enabled: true
    aliases: []
    permission: "xutilities.command.discord"
    message: "<blue>Join our Discord: <click:open_url:'https://discord.gg/example'><hover:show_text:'<gray>Click to open'><underlined>discord.gg/example</underlined></hover></click></blue>"
  store:
    enabled: true
    aliases: []
    permission: "xutilities.command.store"
    message: "<gold>Visit our store: <click:open_url:'https://store.example.com'><hover:show_text:'<gray>Click to open'><underlined>store.example.com</underlined></hover></click></gold>"
  website:
    enabled: true
    aliases: []
    permission: "xutilities.command.website"
    message: "<aqua>Visit our website: <click:open_url:'https://example.com'><hover:show_text:'<gray>Click to open'><underlined>example.com</underlined></hover></click></aqua>"
```
