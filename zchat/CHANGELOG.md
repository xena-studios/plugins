# zChat changelog

## 4.0.0 (unreleased)

Not released yet: 4.0 ships with the other plugins' rewrites, once they've all run on our own servers (ROADMAP.md, Release). Until then 3.2 is the release, and 3.x gets its fixes.

The rewrite: the same formats, filter, commands, permissions and settings as 3.2, now on **Paper, Folia, Spigot and CraftBukkit from 1.8.8 to 26.3**, and **Fabric 1.20.1, 1.21.11 and 26.3** (server-side: players install nothing), with zChat's messages in eight languages. Keep the 3.2 jar to go back to.

### Upgrading from 3.x

Replace the jar and restart. Your `config.yml` is carried forward and the old one is kept beside it as `config.yml.bak-v2` (`config.yml.bak-v1` from 2.x). See [Upgrading from 3.x](https://plugins.xenastudios.co/zchat/upgrade/).

- **Messages are in `lang/` now**, one file per language, no longer in `config.yml`. Every message you changed in 3.x is copied into `lang/en.yml` on the first start, and then zChat sends English to everyone (`messages.language: en`), as 3.x did. Set it to `auto` to give each player their own language. **Chat formats stay in `config.yml`.**
- **`theme` moved into `messages.theme`**, shared with the other Xena plugins in `plugins/Xena/config.yml` unless you set it here. Colours you changed in 3.x stay zChat's own.
- The error messages (`messages.error.*`) are the core's now: `core.command.*` in `lang/`. "No player named ... is online" is the shared "No player called ... is online", unless you'd changed it.
- `update-checker` is `updates` now: `update-checker.enabled` is carried to `updates.check`.
- Remembered `/togglechat` and `/socialspy` choices are kept in `players.records` in zChat's folder; 3.x's, kept on the player, are brought over as each player joins (Bukkit 1.14 and newer). `/zchat forget <player>` deletes a player's.
- **Going back to 3.2:** stop the server, put the 3.2 jar back, and rename `config.yml.bak-v2` to `config.yml` (keep 4.0's as `config.yml.v4`). If you don't, 3.2 still starts and keeps your formats, filter and commands, with its own default messages.

### For developers: the API's breaking changes

- `ZChat.api()` replaces `ZChatApi.get()`. Players are `PlayerRef`s (by UUID), and text is zChat's markup (`String`) instead of Adventure `Component`s, so one API works on every platform.
- `registerMuteProvider(String owner, MuteProvider)`: the provider gets a `PlayerRef` and returns a reason in markup, or `null`.
- The events are zChat's own, on `api.getEvents()` (`ChatMessageEvent.TYPE` and the others), and on Bukkit also Bukkit events in `co.xenastudios.zchat.api.bukkit` (`BukkitChatMessageEvent`, ...). Their messages are plain text; `ChatMessageEvent.placeholder(name, value)` replaces `addPlaceholder`.
- See [Developer API](https://plugins.xenastudios.co/zchat/developer-api/#from-3x) for every change.

### Added

- **Spigot, CraftBukkit and Fabric**, from 1.8.8 and 1.20.1, beside Paper and Folia.
- **Network chat** through **Velocity and BungeeCord**: with zChat on the proxy too, chat reaches every server's players, and `/msg` and `/reply` reach players on any server. Turn it on with `network.enabled` on each server. See [Network chat](https://plugins.xenastudios.co/zchat/network/).
- **Translations:** zChat's messages in German, Spanish, French, Brazilian Portuguese, Russian, Polish and Simplified Chinese, each player in their client's language.
- **`/zchat debug`** (health checks and a report to share) and **`/zchat forget <player>`**.
- **For other plugins:** a vanish plugin can hide players from mentions, `/msg` and its completion, and a plugin can give chat formats tags of its own (`<balance>`).

### Changed

- A message another chat plugin changed before zChat (an `[item]` tag) is shown with its text, but without the hover it added, once zChat draws the format: the core's chat bridge works on text on every platform.
- On Spigot 1.19 and newer and on Fabric, a formatted line is sent as the server's own message, unsigned, as those servers do for any chat a plugin changes. Paper and Folia keep the player's signature.

## 3.2.0

Replace the jar and restart. Your config is kept as it is.

### Added

- A developer API, so other plugins can work with zChat: a punishment plugin can mute players in chat and private messages and show its own reason, other plugins can send announcements in zChat's style (skipping players who hid chat), add their own placeholders to chat formats, and listen for chat messages (before other plugins have their say, and once they're sent), filter catches, private messages and mentions. See [Developer API](https://plugins.xenastudios.co/zchat/developer-api/). It costs nothing unless a plugin uses it: chat takes the same time per message as before.

### Fixed

- `/zchat reload` works on an empty server. Paper pauses a server that's been empty for a minute, and the reload waited for the next player to join.

## 3.1.2

Replace the jar and restart. Your config is kept as it is.

### Fixed

- Chat keeps what another plugin added to a message before zChat, like an `[item]` tag with its hover or a custom emoji. Before, zChat rebuilt the message from its plain text and those were lost. The filter still checks the words; if it censors any, the message is shown as plain text, as before.

## 3.1.1

Replace the jar and restart. Your config is kept as it is.

### Fixed

- The smart filter catches a word split into pieces of one or two letters, like `ba dw or d`. Before, only pieces of one letter (`b a d w o r d`) were caught. The pieces have to make a whole filter word together, so ordinary short words aren't censored, and words with a `*` aren't looked for this way.
- A player who vanishes just as someone mentions them is never pinged. The check for whether the sender can see them now happens again on the main thread, right before the ping.

## 3.1.0

Replace the jar and restart. Your config is kept, with the new `filter.command` settings added.

### Added

- `/filter` manages the chat filter in game: `/filter add <word>`, `/filter remove <word>`, `/filter list`, and `/filter test <message>` to see what the filter would do. Changes are saved to `config.yml`, comments kept, and apply at once. Staff only (`zchat.command.filter`).

### Changed

- The colon in private messages is easier to see: the default formats use `<gray>` for it instead of `<dark_gray>`, which barely showed against chat's background. New configs get it; to update yours, change `<dark_gray>:</dark_gray>` to `<gray>:</gray>` in `private-messages.msg.format-sent` and `format-received`.
- Chat formats with PlaceholderAPI placeholders do a little less work on every message, and notice PlaceholderAPI being enabled or disabled after zChat.

### Fixed

- Only players allowed Minecraft's selectors (`minecraft.command.selector`, operators by default) can use them in `/msg`. Before, any player could message everyone with `/msg @a`, or run a costly `@e[...]` search.
- Turning `/mutechat` off while chat is muted now unmutes it. Before, chat stayed muted until a restart, with no command left to undo it.
- A player's chat state is always cleared when they leave, even when their last message or private message was still being sent as they quit.
- A config file that can't be saved back (a read-only folder, or a mounted volume that can't replace files in one step) no longer loses your settings: they're used as written, with a warning, and backups don't pile up on every start.
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
- An update checker, and [bStats](https://bstats.org/plugin/bukkit/zChat/21533) anonymous usage statistics. See [Privacy](https://plugins.xenastudios.co/start/privacy/) for exactly what each sends, and how to turn them off.

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
