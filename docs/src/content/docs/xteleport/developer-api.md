---
title: Developer API
description: Use xTeleport from your own plugin. Teleport with warmups, cooldowns, costs and safe arrival; read and change homes and warps; find a safe random spot; listen for teleports.
sidebar:
  order: 5
---

This page is for plugin developers. xTeleport has an API so other plugins can give their own teleports the same warmup, cooldown, cost and safety check, read and change homes and warps, and use the `/rtp` search.

The API costs nothing unless a plugin uses it: it adds no listeners and no tasks, and its events are only made when something listens for them.

## Add it to your build

The API is `co.xenastudios.xteleport:xteleport-api`. It's inside the xTeleport jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

:::note
The API is published to Maven Central when xTeleport 1.0.0 is released. Until then, build it from [the source](https://github.com/xena-studios/plugins) with `./gradlew :xteleport:api:publishToMavenLocal` and add `mavenLocal()` to your repositories.
:::

```kotlin
dependencies {
    compileOnly("co.xenastudios.xteleport:xteleport-api:1.0.0")
}
```

```xml
<dependency>
    <groupId>co.xenastudios.xteleport</groupId>
    <artifactId>xteleport-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs xTeleport, in `plugin.yml`:

```yaml
depend: [xTeleport]      # or softdepend, if your plugin works without it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.xteleport.api.XTeleportApi;

XTeleportApi xteleport = XTeleportApi.get();
```

`XTeleportApi.get()` throws an `IllegalStateException` if xTeleport isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(XTeleportApi.class)` works too (and returns null instead of throwing).

Every method says how it runs. Reads answer from memory, from any thread, at once. Changes and searches return a `CompletableFuture` that completes off your thread, often off the server's threads, so hop to the right thread before touching a player or the world (`player.getScheduler()` on Folia). Nothing ever reads the database on your thread.

## Teleport like xTeleport does

```java
xteleport.teleport(player, plotHome, TeleportOptions.defaults().cooldown("plothome", 30).cost(10))
    .thenAccept(result -> {
        if (result == TeleportResult.TELEPORTED) {
            // arrived
        }
    });
```

The player gets xTeleport's warmup (moving or damage cancels it, with the countdown), the cooldown and cost you give, and the safe-arrival check, all with xTeleport's messages. The teleport uses the `COMMAND` cause, so `/back` returns from it. The future always completes. The result is one of `TELEPORTED`, `CANCELLED` (moved or hurt, or xTeleport stopped first), `BLOCKED` (a plugin cancelled `PreTeleportEvent`), `ON_COOLDOWN`, `CANT_AFFORD`, `UNSAFE` or `FAILED` (anything charged was given back).

| Option | Default | Means |
| --- | --- | --- |
| `warmup(seconds)` | -1 | Seconds to stand still. -1 uses xTeleport's own setting, 0 none. |
| `cooldown(key, seconds)` | none | A cooldown still running on the key refuses the teleport; a new one of `seconds` starts once the player arrives. `0` checks the key without starting one. A key xTeleport uses itself, such as `home` or `rtp`, shares that command's cooldown both ways. |
| `cost(amount)` | 0 | Taken through Vault after the warmup. |
| `checkSafe(boolean)` | true | Refuse a spot in lava, inside blocks, over the void, high in the air or outside the world border. |

`cooldown(player, key)` says how long is left on any cooldown.

## Homes, warps and spawn

| Method | Runs | What it does |
| --- | --- | --- |
| `cachedHomes(uuid)` | memory | An online player's homes, or empty if they're offline or still being read. |
| `homes(uuid)` | future | Any player's homes, online or not. |
| `setHome(uuid, name, location)` | future | Sets a home. Doesn't check the limit or fire `HomeSetEvent`. `false` for a name that isn't allowed or a world that isn't loaded. |
| `deleteHome(uuid, name)` | future | Deletes one: `true` if there was one. |
| `homeLimit(player)` | memory | How many homes they may have. |
| `warps()`, `warp(name)` | memory | The warps. |
| `setWarp(name, location)`, `deleteWarp(name)` | future | Changes them; `warps()` shows it at once. |

A change that the database couldn't save completes its future exceptionally, and is undone in memory too, so `cachedHomes` and `warps()` never show what isn't saved. Handle it as you would any failure: tell whoever asked to try again.
| `spawn()` | memory | Where `/spawn` goes. |

`Home` and `Warp` are records with the world's name and the coordinates; `location()` gives a `Location` if the world is loaded.

## A safe random spot

```java
xteleport.findSafeLocation(world, new RtpSettings(0, 0, 500, 3000, 10, false))
    .thenAccept(spot -> spot.ifPresent(location -> { /* ... */ }));
```

The same search `/rtp` uses, with its blocked biomes. It counts towards the limit on searches running at once, so it comes back empty when the server is busy as well as when nothing safe was found.

## Events

| Event | When | Cancel to |
| --- | --- | --- |
| `PreTeleportEvent` | After the warmup and the safety check (unless it's off for that teleport), before paying. Has the destination and the `TeleportType`. | Stop the teleport, as a combat-tag or jail plugin would. |
| `TeleportEvent` | After the player arrived. | |
| `HomeSetEvent` | A player runs `/sethome`, within their limit. | Refuse it, as a claims plugin would in someone else's land. |
| `TpaRequestEvent` | A player sends `/tpa` or `/tpahere`. | Stop the request. |

Each fires on the player's own thread. When you cancel one, xTeleport says nothing to the player, so tell them why.

## Versions

The API follows xTeleport's version: a new minor can add to it, and only a new major can remove anything, after a major of warning. Every type and method says which version added it (`@since`).
