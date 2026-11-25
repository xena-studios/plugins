---
title: Developer API
description: Use zChat from your own plugin. Mute players, send announcements in zChat's style, add placeholders to chat formats, and listen for chat.
sidebar:
  order: 7
---

This page is for plugin developers. zChat 3.2.0 and newer has an API for other plugins: a punishment plugin can mute players in chat and private messages, a vote plugin can announce in zChat's style, and a clan plugin can put a clan tag in the chat format.

The API costs nothing unless a plugin uses it. It adds no listeners and no tasks, and its events are only made when something listens for them, so a chat message takes as long as it did before (about 14 µs in zChat's benchmark, with or without the API).

## Add it to your build

The API is `co.xenastudios.zchat:zchat-api`. It's inside the zChat jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

It's on Maven Central, so `mavenCentral()` is all the repository you need, and its Javadoc is on [javadoc.io](https://javadoc.io/doc/co.xenastudios.zchat/zchat-api).

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("co.xenastudios.zchat:zchat-api:3.2.0")
}
```

Maven:

```xml
<dependency>
    <groupId>co.xenastudios.zchat</groupId>
    <artifactId>zchat-api</artifactId>
    <version>3.2.0</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs zChat, in `plugin.yml`:

```yaml
depend: [zChat]      # or softdepend, if your plugin works without it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.zchat.api.ZChatApi;

ZChatApi zchat = ZChatApi.get();
```

`ZChatApi.get()` throws an `IllegalStateException` if zChat isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(ZChatApi.class)` works too (and returns null instead of throwing). With `softdepend`, check that zChat is enabled before touching any of its classes.

Every method can be called from any thread, answers from memory and returns at once.

## Mute players

A mute provider tells zChat whether a player may send a chat message or a private message (`/msg` and `/reply`). zChat asks every provider before each one, and if a provider gives a reason, the message isn't sent and the player sees the reason as you wrote it.

```java
ZChatApi.get().registerMuteProvider(this, (player, kind) -> {
    Mute mute = mutes.get(player.getUniqueId());   // your own map, kept in memory
    if (mute == null || mute.expired()) {
        return null;                                // they may speak
    }
    return Component.text("You're muted for " + mute.timeLeft() + ".", NamedTextColor.RED);
});
```

`kind` is `MessageKind.CHAT` or `MessageKind.PRIVATE_MESSAGE`, if you mute only one of them.

**Answer from memory.** Providers are asked on chat's threads, for every message, often many at once. Never look anything up in a database there: load mutes when players join and keep them in a concurrent map.

Providers are asked in the order they were registered; the first reason wins. A provider whose plugin is disabled is forgotten, and one that throws is logged once and removed, so a bug can't break chat. Remove one yourself with `unregisterMuteProvider(provider)`.

zChat's own `/mutechat` still applies, and players with `zchat.bypass.mute` skip only `/mutechat`, not your provider.

## Announce in zChat's style

| Method | What it does |
| --- | --- |
| `broadcast(component)` | Sends to every player and the console, with zChat's [theme](/start/formatting/#the-theme) prefix and colour. Players who hid chat with `/togglechat` don't get it. |
| `send(player, component)` | Sends to one player in the same style. Returns false, and sends nothing, if they hid chat. |

```java
ZChatApi.get().broadcast(Component.text(voter.getName() + " voted for the server!"));
```

## What zChat knows

| Method | What it says |
| --- | --- |
| `hasChatHidden(player)` | Whether they hid chat with `/togglechat`. Always false while `/togglechat` is off. |
| `isChatMuted()` | Whether chat is muted for everyone with `/mutechat`. |
| `getFormatName(player)` | Which [format](/zchat/v3/formats/) they get: their group's name (`vip`, `staff`...), or `default`. While `formatting.enabled` is false it's still the group they'd get, though chat isn't drawn in it. |

## Events

Each event is only made when something listens for it.

| Event | When | Thread |
| --- | --- | --- |
| `ChatMessageEvent` | A chat message passed the mute, cooldown, filter and anti-repeat, and is about to be drawn. Cancellable. Has the player, the message and the format's name, and you can add placeholders. Another plugin can still cancel the message after it, so it may not be sent. | The chat thread, usually async |
| `ChatMessageSentEvent` | A chat message is going out: no plugin cancelled it. Has the player, the message and who sees it. For logs and chat bridges. Can't be cancelled. | The chat thread, usually async |
| `FilterMatchEvent` | The filter caught something in a chat or private message, which is already blocked or censored. Has what it matched (`getMatches()`), the kind of message, and whether it was blocked. | The thread the message came from |
| `PrivateMessageEvent` | A `/msg` or `/reply` passed the checks and is about to be delivered. Cancellable, and `setMessage` changes it. | The command's thread |
| `MentionEvent` | A player mentioned in chat is about to hear the ping. Cancellable: they're still highlighted, but hear nothing. | The mentioned player's thread |

`isAsynchronous()` says whether an event fired off the main thread. The events are in `co.xenastudios.zchat.api.event`.

### Add a placeholder to the format

Listen for `ChatMessageEvent` and add a value. A server owner can then use the tag in any format, such as `default-format: "<clan><gray><player>:</gray> <message>"`:

```java
@EventHandler
public void onChat(ChatMessageEvent event) {
    Clan clan = clans.get(event.getPlayer().getUniqueId());   // from memory
    event.addPlaceholder("clan", clan == null ? Component.empty() : Component.text("[" + clan.tag() + "] "));
}
```

Names are lowercase letters, digits, `_` and `-`, and can't be ones zChat's formats already use (`player`, `displayname`, `message`, `papi`, and the theme's `prefix`, `highlight`, `success`, `error`). Like the provider, the listener runs on chat's threads, so answer from memory.

### Alert staff when the filter catches something

```java
@EventHandler
public void onFilter(FilterMatchEvent event) {
    String line = event.getPlayer().getName() + " said " + String.join(", ", event.getMatches());
    staff.forEach(player -> player.sendMessage(Component.text(line)));
}
```

## Versions

The API follows zChat's version: a new minor can add to it, and only a new major can remove anything, after a major of warning. Every type and method says which version added it (`@since`).
