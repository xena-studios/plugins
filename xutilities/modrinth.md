![xUtilities by Xena Studios. A pixel wrench icon and the xUtilities name over a stepped horizon in amber, with the headline "Every command you need. None you don't."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xutilities/banner.png)

## Every command you need. None you don't.

Your players want `/fly`, `/back`, `/craft` and a link to your Discord. Your staff want `/gamemode`, `/heal` and `/clear`. **xUtilities gives you all of it in one small plugin**, and lets you switch off every command you don't use, so your server only has the ones it needs.

Drop it on your lobby, your survival server and your minigames alike. It costs next to nothing to run, and it never takes your server down.

**[Read the docs](https://docs.xenastudios.co/xutilities/)** · **[Commands and permissions](https://docs.xenastudios.co/xutilities/commands/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **Only what you want.** Every command can be turned off, renamed, given its own permission and reworded. A disabled command disappears completely, even from tab completion, and another plugin can have its name.
- **Every change applies on reload.** Turning commands on or off, aliases, permissions, new link commands: `/xutilities reload` applies all of it. No restarts.
- **No lag.** No timers or background tasks. Nothing runs until someone types a command.
- **Never breaks.** A typo in the config falls back to the default with a warning in the console. xUtilities never disables itself or crashes your server.
- **Your words, your colours.** Every message uses MiniMessage, and one `theme` at the top of the config restyles them all at once.
- **Paper and Folia.** One jar runs on Paper, Purpur and Folia, with nothing to set up.

---

## What your players get

**Get around**
`/fly` · `/speed` · `/top` to jump to the surface · `/back` to return to where they were, or where they died

**Workstations anywhere**
`/craft` · `/anvil` · `/smith` · `/grinder` · `/loom` · `/stonecutter` · `/cartography` open the real menus, no block needed, and `/trash` gets rid of junk

**Stay alive**
`/heal` · `/feed` · `/repair` (or `/repair all`) · `/enderchest`

**Your links, one command away**
`/discord`, `/store` and `/website` come ready to fill in, and you can add any others you want, like `/vote` or `/rules`, without writing any code:

    info-commands:
      vote:
        aliases: ["votes"]
        message: "<prefix>Vote for us: <click:open_url:'https://example.com/vote'><highlight><underlined>example.com/vote"

**And for your staff**
`/gamemode` (plus `/survival` `/creative` `/adventure` `/spectator`, or `/gms` `/gmc` `/gma` `/gmsp`) · `/clear` · `/ping` · `/setrotation`, and every one of them works on other players with a separate permission.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server.
2. Put your own links in `plugins/xUtilities/config.yml` and switch off anything you don't need.
3. Give your ranks their permissions. Staff commands are op-only until you do; `/ping` and your link commands work for everyone.

Full setup, every setting and every permission: **[docs.xenastudios.co/xutilities](https://docs.xenastudios.co/xutilities/)**

**Requires** Paper 1.21.11 or newer (or Purpur or Folia) and Java 21 or newer.

**Coming from xUtilities 1.x?** Replace the jar and restart. Your config is carried forward, and the old file is kept as `config.yml.bak-v1`.

---

## More from Xena Studios

- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xutilities)
