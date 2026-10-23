![zChat by Xena Studios. The chat bubble icon and the zChat name over a stepped horizon in green, with "Format your chat. Keep it clean."](../.github/assets/zchat-banner.png)

**Format your chat. Keep it clean.**

zChat handles the chat basics almost every server wants: a chat format per rank, a filter that's hard to dodge, a cooldown and anti-spam, mentions, private messages, a global chat mute, clearing chat, and letting players hide chat for themselves. Every piece can be turned off.

- **Built for the hot path.** zChat runs on every chat message, so that path is kept cheap: formats and the filter are compiled when the config loads, the cheapest checks run first, and each message is drawn once, not once per player who sees it. No repeating tasks.
- **A filter that's hard to dodge.** It catches `s p a c e d` words, `b4dw0rd`, `baaadword` and `bädword`, but leaves innocent words like *class* alone.
- **Players can't inject formatting.** What a player types goes into the format as plain text, unless you give them the colour permission (and even then, only colours and styles).
- **Every change applies on reload**, even turning commands on or off. No restarts.
- **Works with any permissions plugin**, because ranks are picked by permission. PlaceholderAPI is optional.
- **Paper and Folia**, from the same jar.

**[Download on Modrinth](https://modrinth.com/plugin/zchat)** · [Docs](https://docs.xenastudios.co/zchat/) · [Commands and permissions](https://docs.xenastudios.co/zchat/commands/) · [Changelog](CHANGELOG.md)

Needs Paper 1.21.11 or newer (Purpur and Folia too) and Java 21.

## Developing

From the repo root (setup, tests and pull requests are in [CONTRIBUTING.md](../CONTRIBUTING.md)):

```sh
./gradlew :zchat:build       # compile, test, and check the jar's size budget
./gradlew :zchat:runServer   # a Paper server with zChat and PlaceholderAPI, in zchat/run/
```

```
src/main/java/co/xenastudios/zchat/
  ZChat.java       the plugin: declares the config and, in setup, the features it turns on
  chat/            the chat path: the listener, formats, the filter, mentions, anti-repeat
  command/         /mutechat, /clearchat, /togglechat, and private messages (/msg, /reply, social spy)
  config/          the config records; ZConfig carries a zChat 2 config forward
boot-checks.txt    console commands CI runs on real Paper and Folia servers, and what each must log
CHANGELOG.md       one section per release; the release workflow publishes it
modrinth.md        the Modrinth project page, published when it changes on main
```

Anything on the chat path runs for every message, so keep it allocation-light and do the work when the config loads instead. The shared core (reloading, config migration, the theme, the update checker, bStats) is in [`core/`](../core).

## Licence

GPL-3.0, like the rest of the repo. See [LICENSE](../LICENSE).
