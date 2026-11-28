# xVote changelog

## 2.0.0 (unreleased)

Not released yet: 2.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 1.0.1 is the release, and 1.x gets its fixes.

The rewrite: the same commands, permissions, settings, votes, keys and tokens as 1.0.1, now on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), and **Velocity and BungeeCord**, with xVote's messages in eight languages. Keep the 1.0.1 jar to go back to.

### New

- **One receiver for the whole network, on the proxy.** With xVote on Velocity or BungeeCord sharing the servers' MySQL database, every list sends to the proxy; each vote is paid at once on the server the player is on, or on the first server they join. Never twice, however the servers race. See [Networks](https://plugins.xenastudios.co/xvote/networks/).
- **NuVotifier on the proxy works too**: its `proxy` forwarding as it is, and its plugin-message forwarding with `receiver.nuvotifier-channel`.
- **Money through xMoney**, recorded in `/eco history` as xVote's vote reward; Vault's economy as before when xMoney isn't there.
- **Messages in your players' languages**: English, German, Spanish, French, Brazilian Portuguese, Russian, Polish or Simplified Chinese, in `lang/`.
- **`/xvote forget <player>`** deletes a player's votes, totals and held items, for an erasure request, and **`/xvote debug`** writes a report to share when asking for help.

### Upgrading from 1.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v1`; your votes stay where they are (`votes.db`, or your MySQL tables), untouched, and so do `rsa/` and `tokens.yml`. See [Upgrading from 1.x](https://plugins.xenastudios.co/xvote/upgrade/).

- **Messages are in `lang/` now**, one file per language, no longer in `config.yml`; `party.started`, `party.progress` and `reminders.message` too. Every message you changed in 1.x is copied into `lang/en.yml` on the first start, and then xVote sends English to everyone (`messages.language: en`), as 1.x did. A reward's own message and broadcast stay in `config.yml`.
- **`theme` moved into `messages.theme`**, shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here.
- `update-checker` is `updates` now: `update-checker.enabled` is carried to `updates.check`.
- **Going back to 1.0.1:** stop the server, put the 1.0.1 jar back, and rename `config.yml.bak-v1` to `config.yml`. Your votes need nothing: 1.0.1 reads the database as 2.0 left it.

### For developers: the API's breaking changes

- `XVoteApi.get()` is `XVote.api()`, on every platform; players are `PlayerRef`s; `registerRewardType` takes your plugin's name, and a `RewardType` gets a `PlayerRef`.
- The events are xVote's own (`co.xenastudios.xvote.api.event`), with Bukkit events beside them in `co.xenastudios.xvote.api.bukkit`; `VoteCountedEvent` and `VoteRewardedEvent` are new.

## 1.0.1

### Fixed

- xVote no longer keeps voters' IP addresses. Lists send one with each vote; xVote never used it, so it's left out of the database, and the ones 1.0.0 kept are cleared on the first start. Plugins listening for `VoteReceivedEvent` still get it.
- `/xvote token` showed its usage as `[site]`; it takes the service name the list sends, as its reply and the docs say.
- A wrong public key, an unknown service or a bad signature on the `default` token is logged once in all, and the message now says so, rather than "further errors from this list are quiet".
- While Modrinth is still reviewing xVote, the update checker asks GitHub for its releases instead, so you hear about new versions either way. It sends GitHub only xVote's version, as it does Modrinth.

## 1.0.0

The first release. Drop the jar in and start the server: votes are kept in SQLite in its own folder, with nothing to set up. Remove NuVotifier first; xVote takes over its keys, tokens and port.

### Receiving votes

- A Votifier receiver on its own port (8192 by default), speaking Votifier v1 and v2 exactly as NuVotifier does.
- Keys in `rsa/` and tokens in `tokens.yml`, in NuVotifier's own formats. NuVotifier's are taken over on the first start, or with `/xvote import nuvotifier`.
- Limits on connections at once (256), connections open from one address (4), connections per address per minute, and time to send a vote. IPv6 addresses count by their /64. Behind a tunnel such as playit.gg or TCPShield, the limits per address can be raised or turned off.
- A list's wrong token or key is logged once per list, not on every vote; anything else that isn't a vote is logged once per kind, so port scanners can't fill the console or hide a list's mistake. Text from the network loses its control characters before it's logged or stored.
- A failed accept never stops the receiver: it waits a moment and tries again, and `/xvote status` says if it has stopped.
- The private key, `tokens.yml` and their backups are readable by the server's own user only.
- `/vote test <player> [site]` sends a signed vote through the receiver's own port, end to end.
- `/xvote status` shows the receiver, votes waiting, and the service name each list sends; `/xvote key` and `/xvote token` show what lists ask for.

### Counting

- Every vote is kept. Totals per day, week and month in your time zone, all-time totals, streaks and leaderboards.
- Offline players are paid when they join. Votes for a name that has never joined wait until it does, and Bedrock players who leave off Floodgate's prefix are still matched.
- A vote from the same site within its cooldown, or a replayed one, is dropped. Sites that aren't listed have a cooldown too, `counting.unlisted-cooldown-hours` (24 by default).
- Votes taken to pay when the server stops, before the player got them, are paid the next time instead.

### Rewards

- Money through Vault, items (held for `/vote claim` when they don't fit), console commands, messages, broadcasts and sounds, each with a chance. In commands, `<site>` is a listed site's key, and that of a site that isn't listed keeps only letters, digits, `.`, `_` and `-`.
- Given on every vote, per site, on the first vote of the day, every Nth vote, at streak milestones and at vote totals.
- A vote party every so many votes, for everyone online or everyone who voted.
- Reminders on join, and every so many minutes if you want them.

### Players

- `/vote` lists every site with its link and when it opens again; `/vote top` the leaderboards; `/vote claim` held items.
- PlaceholderAPI placeholders for totals, streaks, sites open, the party and the top voters.

### Networks

- Servers sharing one MySQL or MariaDB database pay a player on whichever server they're on, and never pay a vote twice. The vote party starts everywhere at once.
- A server that couldn't reach the database for a while reads the leaderboards and the party again when it can, and pays the players on it whatever arrived meanwhile.

### Switching

- `/xvote import votingplugin` and `/xvote import superbvote` add every player's all-time total and when they last voted, read from VotingPlugin's SQLite `Users` table and SuperbVote's JSON file or MySQL table.

### For developers

- `xvote-api`: totals and leaderboards from memory, reward types for the config, `submitVote` for votes from elsewhere, and `VoteReceivedEvent`, `VoteRewardEvent` and `VotePartyEvent`.
