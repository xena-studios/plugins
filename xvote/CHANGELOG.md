# xVote changelog

## 1.0.0

The first release. Drop the jar in and start the server: votes are kept in SQLite in its own folder, with nothing to set up. Remove NuVotifier first; xVote takes over its keys, tokens and port.

### Receiving votes

- A Votifier receiver on its own port (8192 by default), speaking Votifier v1 and v2 exactly as NuVotifier does.
- Keys in `rsa/` and tokens in `tokens.yml`, in NuVotifier's own formats. NuVotifier's are taken over on the first start, or with `/xvote import nuvotifier`.
- Limits on connections per address per minute, connections at once, and time to send a vote. A list's wrong token is logged once, not on every vote.
- `/vote test <player> [site]` sends a signed vote through the receiver's own port, end to end.
- `/xvote status` shows the receiver, votes waiting, and the service name each list sends; `/xvote key` and `/xvote token` show what lists ask for.

### Counting

- Every vote is kept. Totals per day, week and month in your time zone, all-time totals, streaks and leaderboards.
- Offline players are paid when they join. Votes for a name that has never joined wait until it does, and Bedrock players who leave off Floodgate's prefix are still matched.
- A vote from the same site within its cooldown, or a replayed one, is dropped.

### Rewards

- Money through Vault, items (held for `/vote claim` when they don't fit), console commands, messages, broadcasts and sounds, each with a chance.
- Given on every vote, per site, on the first vote of the day, every Nth vote, at streak milestones and at vote totals.
- A vote party every so many votes, for everyone online or everyone who voted.
- Reminders on join, and every so many minutes if you want them.

### Players

- `/vote` lists every site with its link and when it opens again; `/vote top` the leaderboards; `/vote claim` held items.
- PlaceholderAPI placeholders for totals, streaks, sites open, the party and the top voters.

### Networks

- Servers sharing one MySQL or MariaDB database pay a player on whichever server they're on, and never pay a vote twice. The vote party starts everywhere at once.

### Switching

- `/xvote import votingplugin` and `/xvote import superbvote` add every player's all-time total.

### For developers

- `xvote-api`: totals and leaderboards from memory, reward types for the config, `submitVote` for votes from elsewhere, and `VoteReceivedEvent`, `VoteRewardEvent` and `VotePartyEvent`.
