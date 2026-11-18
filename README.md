![Xena Studios plugins. The xUtilities, zChat, xLimbo and xRewind icons and names over a stepped horizon in their four accents, with "Small, fast plugins for Paper servers."](.github/assets/banner.png)

**Small, fast plugins for Paper servers.**

By [Xena Studios](https://xenastudios.co). Each does one job, costs next to nothing to run, and lets you switch off what you don't use. Every setting applies on reload, and old configs carry forward on update.

<!-- catalog:start: the plugins, from the brand catalog. Edit them there, then run pnpm catalog in docs/. -->
**Players**

| | Plugin | | |
|---|---|---|---|
| <img src="docs/src/assets/plugins/xutilities.svg" width="40" alt=""> | **[xUtilities](xutilities)** | Every command you need. None you don't. | [Docs](https://docs.xenastudios.co/xutilities/) · [Modrinth](https://modrinth.com/plugin/xutilities) |
| <img src="docs/src/assets/plugins/zchat.svg" width="40" alt=""> | **[zChat](zchat)** | Format your chat. Keep it clean. | [Docs](https://docs.xenastudios.co/zchat/) · [Modrinth](https://modrinth.com/plugin/zchat) |
| <img src="docs/src/assets/plugins/xteleport.svg" width="40" alt=""> | **[xTeleport](xteleport)** | Every teleport your server needs. | [Docs](https://docs.xenastudios.co/xteleport/) · Coming soon |
| <img src="docs/src/assets/plugins/xstats.svg" width="40" alt=""> | **[xStats](xstats)** | Every player's stats, on your own website. | [Docs](https://docs.xenastudios.co/xstats/) · Coming soon |

**Moderation**

| | Plugin | | |
|---|---|---|---|
| <img src="docs/src/assets/plugins/xrewind.svg" width="40" alt=""> | **[xRewind](xrewind)** | Every change, logged. Any of it, undone. | [Docs](https://docs.xenastudios.co/xrewind/) · [Modrinth](https://modrinth.com/plugin/xrewind) |
| <img src="docs/src/assets/plugins/xbans.svg" width="40" alt=""> | **[xBans](xbans)** | Bans, mutes and warnings. Nothing slips through. | [Docs](https://docs.xenastudios.co/xbans/) · Coming soon |

**Economy**

| | Plugin | | |
|---|---|---|---|
| <img src="docs/src/assets/plugins/xplots.svg" width="40" alt=""> | **[xPlots](xplots)** | Rentable plots, set up in a minute. | [Docs](https://docs.xenastudios.co/xplots/) · Coming soon |
| <img src="docs/src/assets/plugins/xvote.svg" width="40" alt=""> | **[xVote](xvote)** | Votes in. Rewards out. | [Docs](https://docs.xenastudios.co/xvote/) · Coming soon |
| <img src="docs/src/assets/plugins/xmoney.svg" width="40" alt=""> | **[xMoney](xmoney)** | The economy and Vault, in one plugin. | [Docs](https://docs.xenastudios.co/xmoney/) · Coming soon |

**Server**

| | Plugin | | |
|---|---|---|---|
| <img src="docs/src/assets/plugins/xlimbo.svg" width="40" alt=""> | **[xLimbo](xlimbo)** | Never drop a player again. | [Docs](https://docs.xenastudios.co/xlimbo/) · [Modrinth](https://modrinth.com/plugin/xlimbo) |
| <img src="docs/src/assets/plugins/xbackup.svg" width="40" alt=""> | **[xBackup](xbackup)** | Backups that cost nothing. Restores you can trust. | [Docs](https://docs.xenastudios.co/xbackup/) · Coming soon |

All eleven need Paper 1.21.11+ and Java 21+. xUtilities, zChat, xRewind, xTeleport, xVote, xBans, xMoney, xStats and xBackup also run on Folia. Get them on [Modrinth](https://modrinth.com/organization/xena-studios); the [docs](https://docs.xenastudios.co) cover setup, commands, permissions and config.
<!-- catalog:end -->

## Building

Needs JDK 21 or newer.

```sh
./gradlew build                  # every plugin, tested, into <plugin>/build/libs/
./gradlew :<plugin>:runServer    # a Paper server with that plugin
```

## Contributing

Bugs and ideas go in an [issue](https://github.com/xena-studios/plugins/issues/new/choose). Security problems go privately, as [SECURITY.md](SECURITY.md) describes. For anything else, see [SUPPORT.md](SUPPORT.md).

[CONTRIBUTING.md](CONTRIBUTING.md) covers how the repo fits together, tests, the rules a change keeps, pull requests and releases. [DECISIONS.md](DECISIONS.md) explains why the plugins are built the way they are.

## Licence

[GPL-3.0-only](LICENSE).
