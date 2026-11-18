---
title: Developer API
description: Use xLimbo from your own plugin. Check the main server and the return queue, send players back, and listen for arrivals and returns.
sidebar:
  order: 8
---

This page is for plugin developers. xLimbo 2.1.0 and newer has an API for other plugins on the limbo server: show players their place in line your own way, send them back from your own command or menu, or log who arrives and why.

The API costs nothing unless a plugin uses it. It adds no listeners and no tasks: the status check still runs only while [server-check](/xlimbo/network-setup/) is on, the queue was already in memory, and the events are only made when something listens for them. Like xLimbo, it's for Paper (and Purpur), not Folia.

## Add it to your build

The API is `co.xenastudios:xlimbo-api`. It's inside the xLimbo jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

:::note
The API is published to Maven Central when xLimbo 2.1.0 is released. Until then, build it from [the source](https://github.com/xena-studios/plugins) with `./gradlew :xlimbo:api:publishToMavenLocal` and add `mavenLocal()` to your repositories.
:::

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("co.xenastudios:xlimbo-api:2.1.0")
}
```

Maven:

```xml
<dependency>
    <groupId>co.xenastudios</groupId>
    <artifactId>xlimbo-api</artifactId>
    <version>2.1.0</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs xLimbo, in `plugin.yml`:

```yaml
depend: [xLimbo]      # or softdepend, if your plugin works without it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.xlimbo.api.XLimboApi;

XLimboApi xlimbo = XLimboApi.get();
```

`XLimboApi.get()` throws an `IllegalStateException` if xLimbo isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(XLimboApi.class)` works too (and returns null instead of throwing). With `softdepend`, check that xLimbo is enabled before touching any of its classes.

Every method can be called from any thread and returns at once.

## The main server and the queue

| Method | What it says |
| --- | --- |
| `isServerCheckEnabled()` | Whether `server-check` is on. |
| `isMainServerUp()` | Whether the main server answered xLimbo's last status check. False if it didn't, if it hasn't been asked yet, or if server-check is off. |
| `getQueuePosition(player)` | Their place in line to go back, from 1, as an `OptionalInt`. Empty if they aren't waiting. |
| `getQueueSize()` | How many players are waiting. 0 while the queue is off. |

All of these answer from memory: the status check runs in the background, and the queue's numbers are a snapshot taken every second.

```java
OptionalInt place = XLimboApi.get().getQueuePosition(player);
if (place.isPresent()) {
    player.sendMessage(Component.text("You're number " + place.getAsInt() + " in line."));
}
```

## Send players back

| Method | What it does |
| --- | --- |
| `sendBack(player)` | Sends the player back to the main server. |
| `sendBackAll()` | The same, for everyone in limbo. |

While the queue is on (`auto-join` or `server-check`), players join the end of it and go in turn, `queue.per-second` at a time, so a crowd doesn't flood the main server. With the queue off, they're sent at once. While server-check says the main server is down, nobody is sent: everyone goes back when it comes up.

Both can be called from any thread. The work happens on the main thread: at once if you call from it, otherwise on the next tick.

## Events

Each event is only made when something listens for it. They're in `co.xenastudios.xlimbo.api.event`.

| Event | When | Thread |
| --- | --- | --- |
| `PlayerEnterLimboEvent` | A player arrived. `getReason()` says why, as far as xLimbo can tell (below). | Main |
| `PlayerReturnEvent` | A player is about to be sent back. Cancellable. `getCause()` is `QUEUE`, `JOIN_COMMAND` or `PLUGIN` (your `sendBack` while the queue is off), and `getServer()` is where they're going. | Main |
| `MainServerStatusEvent` | The main server went up or down (`isUp()`). Only while server-check is on, and only for a change: the first answer after server-check starts isn't one. | Async |

### Why a player arrived

Proxies (Velocity, BungeeCord) don't tell the server they send a player to why they did it, so the reason is what xLimbo can work out for itself:

| Reason | Meaning |
| --- | --- |
| `MAIN_SERVER_DOWN` | Server-check says the main server is down. |
| `REFUSED` | xLimbo sent them back moments ago (15 seconds) and they're here again, so the main server turned them away: it's full, they're banned, or it isn't ready. |
| `UNKNOWN` | Anything else: kicked here by the proxy, sent on purpose, or joined limbo directly. |

### Keep a player in limbo

A cancelled return says nothing to the player, so tell them why. A cancelled automatic return is tried again on a later pass of the queue, so your listener can be asked again for the same player.

```java
@EventHandler
public void onReturn(PlayerReturnEvent event) {
    if (stillInTutorial(event.getPlayer())) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Finish the tutorial first!"));
    }
}
```

## Versions

The API follows xLimbo's version: a new minor can add to it, and only a new major can remove anything, after a major of warning. Every type and method says which version added it (`@since`).
