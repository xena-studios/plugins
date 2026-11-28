# xStats changelog

## 2.0.0 (unreleased)

Not released yet: 2.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 1.0.1 is the release, and 1.x gets its fixes.

The rewrite: the same commands, permissions, settings, stats, boards and website as 1.0.1, now on **Paper, Folia, Spigot and CraftBukkit from 1.13 to 26.3**, **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), and **Velocity**, with xStats' messages in eight languages. Keep the 1.0.1 jar to go back to.

### New

- **The whole network on the proxy.** With xStats on Velocity sharing the servers' MySQL database (with `network.enabled`), `/networkstats` and `/networkleaderboard` answer from memory wherever a player is, even on a server without xStats, and the website can run there for the whole network. See [Networks](https://plugins.xenastudios.co/xstats/networks/).
- **The family's stats.** With xVote, xMoney, xBans, xTeleport, xRewind, xMarket or zChat installed, their numbers are stats like any other: `xvote_votes`, `xmoney_balance` and `xmoney_earned`, `xbans_punishments`, `xteleport_teleports`, `xrewind_rollbacks`, `xmarket_bought` and `xmarket_sold`, `zchat_messages`. Each can be turned off under `integrations`.
- **Players xVanish hides** are left out of leaderboards while they're hidden, and shown as offline on the website.
- **Placeholders on Fabric** through Text Placeholder API: `%xstats:stat kills_monthly%` and the rest, and `%xstats:kd%`.
- **Messages in your players' languages**: English, German, Spanish, French, Brazilian Portuguese, Russian, Polish or Simplified Chinese, in `lang/`.
- **`/xstats migrate <sqlite|mysql>`** moves the stats between SQLite and MySQL in place: copied, counted, then used at once without a restart (saves wait while it copies).
- **`/xstats forget <player>`** deletes everything xStats keeps about a player, for an erasure request, and **`/xstats debug`** writes a report to share when asking for help.
- **For developers**: the API is the same on every platform, with players as `PlayerRef`s; `registerStat` takes decimals, `unregisterStats` takes a plugin's stats out of use, and two cancellable events join the two 1.x had: `StatChangeEvent` and `PrivacyChangeEvent`.

### Upgrading from 1.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v1`; your stats stay where they are (`stats.db`, or your MySQL tables), untouched, at schema 2, and so do your boards. See [Upgrading from 1.x](https://plugins.xenastudios.co/xstats/upgrade/).

- **Messages are in `lang/` now**, one file per language, no longer in `config.yml`; the boards' words and the period names too. Every message you changed in 1.x is copied into `lang/en.yml` on the first start, and then xStats sends English to everyone (`messages.language: en`), as 1.x did.
- **`theme` moved into `messages.theme`**, shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here.
- `update-checker` is `updates` now: `update-checker.enabled` is carried to `updates.check`.
- The API changed: see [Developer API](https://plugins.xenastudios.co/xstats/developer-api/#from-1x).

## 1.0.1

### Fixed

- The website's "Stats by xStats" link opens xStats' docs, which stay up whatever happens to its Modrinth page.
- While Modrinth is still reviewing xStats, the update checker asks GitHub for its releases instead, so you hear about new versions either way. It sends GitHub only xStats' version, as it does Modrinth.

## 1.0.0

The first release. Drop the jar in and start the server: stats are kept in SQLite in its own folder, with nothing to set up, and every player's history is read from the world's stats files on first start.

### Stats

- 14 stats out of the box: playtime, sessions, player kills, deaths, mobs killed, blocks mined, distance travelled, fish caught, jumps, damage dealt, animals bred, villager trades, items enchanted and raids won.
- Any of Minecraft's own stats as a stat, named as `/scoreboard` names them, alone or added up: `mined:diamond_ore + mined:deepslate_diamond_ore`, `killed:*`. Change what a stat adds up later and it starts again from the new total, none of it counted as today's.
- Playtime that leaves out time a player didn't move their camera between two saves.
- Any PlaceholderAPI value as a stat, read every save and when a player leaves: a balance, a jobs level. Colour codes are skipped, and `5 blocks` reads as 5.
- Daily, weekly and monthly numbers for the stats you choose, in your time zone; days older than 90 are added into their month.
- On first start, every player's history from `world/stats` (or `world/players/stats` on 26.x), playtime and sessions included for players who joined before it ran. `/xstats import` runs it again, never adding anything twice.

### For players

- `/stats [player]`: a player's stats and places, online or not, with a link to their page on the website.
- `/stats private`: off the website straight away, every page of it, and on again.
- `/leaderboard <stat> [daily|weekly|monthly|all] [page]` (`/lb`), a page at a time.

### Leaderboards

- Worked out at each save and answered from memory, for `/leaderboard`, placeholders, boards and the website.
- Players with `xstats.leaderboard.exclude`, and players banned with `/ban`, left out.
- Boards in the world with `/xstats board add`: Paper text displays, no hologram plugin.
- PlaceholderAPI: `%xstats_<stat>%`, per period, `_raw`, `%xstats_rank_<stat>%`, `%xstats_top_<stat>_<n>_name%` and `_value`, and `%xstats_kd%`.

### The website

- A stats website served by xStats itself, off until you turn it on, in your server's name, logo and colours, dark or light, and made for phones too:
  - an overview with each stat's leaders for any period, and the server's totals;
  - leaderboards with a podium for the top three, every period, and a box to find a player on the board;
  - a page for every player: their head, when they were last on, badges for their best places, a chart of their last 30 days, each stat with today's, this week's and this month's, and what other plugins add. Bedrock players (through Floodgate) have pages too;
  - search with suggestions, and `/` to jump to it.
- A read-only, versioned JSON API, with CORS for sites hosted elsewhere, a limit per visitor, and caching.
- Works behind a reverse proxy, Cloudflare or a Cloudflare Tunnel, counting each visitor by the address your proxy passes on in the one header you choose, so visitors can't pick their own.
- Heads from mc-heads.net, any service you set, or fetched and kept by xStats so visitors never contact a third party.
- `/xstats web export` writes the site to host on GitHub Pages, Cloudflare Pages or your own domain. `/xstats web test` checks it on your server first.

### Also

- Networks: several servers sharing MySQL, with leaderboards and the website counting them all, and each server's numbers on player pages. A shared balance is the latest value any server read.
- Stats waiting for a save are kept in order and added up, so a database outage never doubles a counter, and other plugins' increments never pile up in memory.
- A developer API: read stats, ranks and leaderboards, add stats of your own and sections to player profiles, and listen for saves and new leaders.
- Paper and Folia, 1.21.11 and 26.x.
