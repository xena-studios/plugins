---
title: Developer API
description: Use xUtilities from your own plugin. Read, set and clear /back locations, check which commands are on, and listen for /back.
sidebar:
  order: 5
---

This page is for plugin developers. xUtilities 2.1.0 and newer has a small API for other plugins. Most of it is about `/back`: a teleport plugin can make `/back` work for its own teleports, and a minigame or a jail can keep `/back` from returning into it.

The API costs nothing unless a plugin uses it: it adds no listeners and no tasks, and its events are only made when something listens for them.

## Add it to your build

The API is `co.xenastudios.xutilities:xutilities-api`. It's inside the xUtilities jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

It's on Maven Central, so `mavenCentral()` is all the repository you need, and its Javadoc is on [javadoc.io](https://javadoc.io/doc/co.xenastudios.xutilities/xutilities-api).

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("co.xenastudios.xutilities:xutilities-api:2.1.0")
}
```

Maven:

```xml
<dependency>
    <groupId>co.xenastudios.xutilities</groupId>
    <artifactId>xutilities-api</artifactId>
    <version>2.1.0</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs xUtilities, in `plugin.yml`:

```yaml
depend: [xUtilities]      # or softdepend, if your plugin works without it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.xutilities.api.XUtilitiesApi;

XUtilitiesApi xutilities = XUtilitiesApi.get();
```

`XUtilitiesApi.get()` throws an `IllegalStateException` if xUtilities isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(XUtilitiesApi.class)` works too (and returns null instead of throwing). With `softdepend`, check that xUtilities is enabled before touching any of its classes.

Every method can be called from any thread. Each answers from memory and returns at once.

## /back

| Method | What it does |
| --- | --- |
| `isBackEnabled()` | Whether `/back` is turned on. While it's off, nothing is remembered. |
| `getBackLocation(player)` | Where `/back` would take the player, as an `Optional<Location>` (a copy). Empty if there's nowhere, or `/back` is off. |
| `setBackLocation(player, location)` | Sets where `/back` takes the player. Returns false if `/back` is off or the player isn't online. Throws an `IllegalArgumentException` if the location's world isn't loaded. |
| `clearBackLocation(player)` | Forgets where `/back` would take the player. |

xUtilities already remembers teleports by commands and plugins (the `COMMAND`, `PLUGIN` and `UNKNOWN` causes) and deaths. Use `setBackLocation` for anything else, such as a teleport with another cause, or a move your plugin makes some other way:

```java
Location from = player.getLocation();
player.teleportAsync(home, PlayerTeleportEvent.TeleportCause.ENDER_PEARL).thenAccept(moved -> {
    if (moved) {
        XUtilitiesApi.get().setBackLocation(player, from);
    }
});
```

A spot is forgotten when the player leaves the server.

## Commands

`isCommandEnabled(label)` says whether xUtilities has a command on under that name or alias: one of its commands, a [link command](/xutilities/v2/link-commands/), or `/xutilities` itself (and `/xu`). A leading `/`, the `xutilities:` prefix and upper case are fine. Use it to avoid registering a command that clashes, or to point players at the right one:

```java
if (!XUtilitiesApi.get().isCommandEnabled("spawn")) {
    registerMySpawnCommand();
}
```

True means xUtilities registered the command, not that `/spawn` runs it. If another plugin had `/spawn` first, it keeps it and xUtilities' is only `/xutilities:spawn`. Bukkit's command map (`Bukkit.getCommandMap().getCommand("spawn")`) says who has the bare label.

## Events

Both are cancellable, fired on the player's thread (the main thread on Paper, the player's region thread on Folia), and only made when something listens for them.

| Event | When |
| --- | --- |
| `BackRecordEvent` | A spot is about to be saved as where `/back` takes a player. `getReason()` is `TELEPORT` or `DEATH`. Cancel it to keep the spot they had. Not fired for `setBackLocation`. |
| `BackTeleportEvent` | A player used `/back` and is about to be teleported to `getDestination()`. Cancel it to keep them where they are; xUtilities tells them nothing, so tell them why. |

Keep `/back` from returning into an arena:

```java
@EventHandler
public void onBackRecord(BackRecordEvent event) {
    if (arena.contains(event.getLocation())) {
        event.setCancelled(true);
    }
}
```

The events are in `co.xenastudios.xutilities.api.event`.

## Versions

The API follows xUtilities' version: a new minor can add to it, and only a new major can remove anything, after a major of warning. Every type and method says which version added it (`@since`).
