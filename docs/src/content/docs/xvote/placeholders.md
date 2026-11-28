---
title: Placeholders
description: "xVote's PlaceholderAPI placeholders: totals, streaks, the sites open, the vote party and the leaderboards."
sidebar:
  order: 5.5
---

With PlaceholderAPI on Bukkit, answered from memory:

| Placeholder | Is |
| --- | --- |
| `%xvote_total%`, `%xvote_today%`, `%xvote_week%`, `%xvote_month%` | The player's votes. |
| `%xvote_streak%`, `%xvote_best_streak%` | Their streak now, and their longest. |
| `%xvote_available%` | How many sites they can vote on now. |
| `%xvote_party_votes%`, `%xvote_party_needed%`, `%xvote_party_left%` | The vote party's count. |
| `%xvote_top_<period>_<n>_name%`, `%xvote_top_<period>_<n>_votes%` | The leaderboards: `<period>` is `day`, `week`, `month` or `all`; blank (or `0`) past the end. |
