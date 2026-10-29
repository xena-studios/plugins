# zChat changelog

## Unreleased

### Fixed

- Clicking the link in the update notice now opens the new version's Modrinth page. Before, it opened nothing.

## 3.0.0

A rewrite. Replace the jar and restart: your zChat 2 config is carried forward, and the old file is kept as `config.yml.bak-v1`. A zChat 1.x config can't be carried forward: it's kept as `config.yml.old`, and a fresh config is written.

**Needs Paper 1.21.11 or newer** (Purpur and Folia too) and Java 21. zChat 2 ran on 1.20.6 and newer.

### Everything applies on reload

- `/zchat reload` now applies every setting. Turning a command on or off, its aliases and permission no longer need a restart. The mute, hidden chat and reply targets last through it.
- A config file that isn't valid YAML no longer loses your settings: zChat says where the error is and keeps running with the settings it had (or the defaults, at startup) until it's fixed.
- `/zchat reload` says how many settings fell back to their defaults, and `/zchat info` shows which commands are on, whether an update is out, and any config problems. `/zc` is short for `/zchat`.

### New

- **A smarter filter.** `words` are matched as whole words, so innocent words like *class* are left alone, and with `smart: true` (the default) the filter also catches s p a c e d, b4dw0rd, baaadword, bädword, full-width letters and invisible characters. It censors exactly what was typed. Colour tags can't hide a word from it. `patterns` still take regular expressions.
- **Anti-repeat**: stops a player sending the same, or nearly the same, message again within 30 seconds.
- **Mentions**: a player's name in chat is highlighted and pings them. `@everyone` for players with `zchat.mention.everyone`.
- **Private messages**: `/msg` (replacing Minecraft's `/msg`, `/tell` and `/w`, selectors included on Paper), `/reply` and `/socialspy`. The filter applies to them.
- `/togglechat` remembers a player's choice when they rejoin (`togglechat.remember`).
- A `theme` at the top of the config: a prefix and four colours zChat's own messages use. If you never changed a zChat 2 message, it gets the new look; any you changed are kept.
- An update checker, and [bStats](https://bstats.org/plugin/bukkit/zChat/21533) anonymous usage statistics. See [Privacy](https://docs.xenastudios.co/start/privacy/) for exactly what each sends, and how to turn them off.

### Changed

- **The `zchat.group.*` permissions no longer go to operators by default.** Give your ranks their group permission; an operator no longer gets the admin format just for being an operator.
- PlaceholderAPI values now go into a format as text, after it's read, so a placeholder showing something a player chose, such as a nickname, can't add clickable links or hover text to chat. `%placeholders%` in formats work as before.
- `<displayname>` keeps a nickname's colours but not its clicks or hovers.
- Mentions ping, and messages count toward the cooldown and anti-repeat, only once no other plugin has cancelled them.
- `/clearchat` sends its blank lines as one message rather than one per line.
- The example `filter.patterns` are now example `words`. If you still had zChat 2's `badword1` and `badword2` patterns, they're replaced; your own patterns are kept.
- Each chat line is drawn once and the same line sent to everyone who sees it.
- `cooldown.seconds` can have decimals.
- Players you can't see count as offline for `/msg` and tab completion, so vanished staff stay hidden.
