![xStats by Xena Studios. A green pixel podium icon and the xStats name over a stepped horizon in green, with the headline "Every player's stats, on your own website."](https://raw.githubusercontent.com/xena-studios/brand/main/plugins/xstats/banner.png)

## Every player's stats, on your own website.

Players want to see their stats and where they rank. Owners want leaderboards on the scoreboard and a stats page to share. Usually that's three plugins and a web host. **xStats is one plugin: player stats, leaderboards, placeholders and a stats website your server hosts itself, with nothing to sign in to.**

**[Read the docs](https://docs.xenastudios.co/xstats/)** · **[Report a bug](https://github.com/xena-studios/plugins/issues/new/choose)**

---

## Why server owners pick it

- **History from day one.** On first start it reads every player's stats from your world's own files, so leaderboards aren't empty on launch day.
- **Playtime that leaves out AFK time.** Minecraft counts time spent away from the keyboard. xStats doesn't.
- **Leaderboards by day, week, month and all time,** in `/leaderboard`, in placeholders and as boards in your world, with staff and banned players left out.
- **A website, built in.** Player search, a page for every player with their head, ranks and the last 30 days, leaderboards and server totals, in your server's name, logo and colours. Or copy it to GitHub Pages or your own domain.
- **You won't feel it running.** One save every five minutes is its only task. It reads each player on their own thread, a few a tick, from the counters the server already keeps: no listeners for kills, blocks or movement. Leaderboards are worked out during the save and answered from memory.
- **Private when players want.** `/stats private` takes a player off the website. No IP addresses are stored, and the site shows only the stats you choose.
- **Every feature free.** No early-access builds, nothing behind a Patreon.

---

## What players get

- **`/stats [player]`:** anyone's stats and where they rank, online or not, with a link to their page on the website.
- **`/lb kills monthly`:** any leaderboard, a page at a time.
- **A page of their own** on your stats website, to share with friends.

---

## What you get

- **Any stat:** all of Minecraft's (`player_kills`, `mined:diamond_ore`, `killed:*`), added up however you like, and any PlaceholderAPI value, such as a balance or a jobs level.
- **PlaceholderAPI:** `%xstats_kills%`, `%xstats_kills_monthly%`, `%xstats_rank_kills_monthly%`, `%xstats_top_kills_monthly_1_name%`, for scoreboards, TAB and holograms.
- **Boards in the world:** `/xstats board add kills monthly`, as Paper text displays, no hologram plugin needed.
- **A website that's safe to expose:** read-only, nothing to log in to, a limit per visitor, and tested setups behind a reverse proxy or a Cloudflare Tunnel.
- **Build your own:** a documented, versioned JSON API, and documented database tables.
- **Networks:** several servers sharing MySQL, with network-wide leaderboards and one website.
- **A developer API** for other plugins to add their own stats and profile sections.

---

## Up and running in a minute

1. Drop the jar in `plugins/` and start the server. Stats are kept in SQLite, with nothing to set up, and everyone's history is imported.
2. Try `/stats` and `/lb playtime`.
3. For the website, set `web.enabled: true` in `plugins/xStats/config.yml` and `/xstats reload`.

Which stats, periods, colours and every message are in the config, and apply on `/xstats reload`.

Full setup, every setting and every permission: **[docs.xenastudios.co/xstats](https://docs.xenastudios.co/xstats/)**

**Requires** Paper 1.21.11 or newer (or Purpur, or Folia) and Java 21 or newer. PlaceholderAPI is optional. SQLite out of the box, or MySQL/MariaDB.

---

## More from Xena Studios

- **[xUtilities](https://modrinth.com/plugin/xutilities)**: Every command you need. None you don't. Gamemodes, fly, heal, `/back`, workstations and your own link commands.
- **[zChat](https://modrinth.com/plugin/zchat)**: Format your chat. Keep it clean. Chat formats per rank, a filter that's hard to dodge, mentions and private messages.
- **[xLimbo](https://modrinth.com/plugin/xlimbo)**: Never drop a player again. A fallback server that holds players when your main server goes down, and sends them back when it's up.
- **[xRewind](https://modrinth.com/plugin/xrewind)**: Every change, logged. Any of it, undone. A block logger with rollback, and a free CoreProtect alternative.
- **[xPlots](https://modrinth.com/plugin/xplots)**: Rentable plots, set up in a minute. Select an area and every plot and sign is made for you.

Made by **[Xena Studios](https://xenastudios.co)** · Open source under GPL-3.0-only · [Source](https://github.com/xena-studios/plugins/tree/main/xstats)
