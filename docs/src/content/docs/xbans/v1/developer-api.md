---
title: Developer API
description: Use xBans from your own plugin. Check bans and mutes from memory, punish and revoke from an anti-cheat or a Discord bot, read history, and listen for punishments.
sidebar:
  order: 7
---

This page is for plugin developers. xBans has an API for other plugins: an anti-cheat can ban, a Discord bot can mute and unmute, a vote plugin can skip banned players, and a stats plugin can show a player's history.

The API costs nothing unless a plugin uses it. It adds no listeners and no tasks, and its events are only made when something listens for them.

## Add it to your build

The API is `co.xenastudios.xbans:xbans-api`. It's inside the xBans jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

It's on Maven Central, so `mavenCentral()` is all the repository you need, and its Javadoc is on [javadoc.io](https://javadoc.io/doc/co.xenastudios.xbans/xbans-api).

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("co.xenastudios.xbans:xbans-api:1.0.1")
}
```

Maven:

```xml
<dependency>
    <groupId>co.xenastudios.xbans</groupId>
    <artifactId>xbans-api</artifactId>
    <version>1.0.1</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs xBans, in `plugin.yml`:

```yaml
depend: [xBans]      # or softdepend, if your plugin works without it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.xbans.api.XBansApi;

XBansApi xbans = XBansApi.get();
```

`XBansApi.get()` throws an `IllegalStateException` if xBans isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(XBansApi.class)` works too (and returns null instead of throwing).

There are two kinds of method:

- **Checks** (`isBanned`, `isMuted`, `getActiveBan`, `getActiveMute`) can be called from any thread, answer from memory and return at once. They're cheap enough for a join or chat listener.
- **Everything else** returns a `CompletableFuture` that completes off the server's threads. Hop back to the right thread before touching a player or the world.

## Check

```java
UUID id = player.getUniqueId();
if (xbans.isMuted(id)) {
    Punishment mute = xbans.getActiveMute(id).orElseThrow();
    player.sendMessage("Muted until " + (mute.isPermanent() ? "forever" : mute.expires()));
}
```

A check answers for this server: a ban scoped to another server of a network doesn't count here. `isMuted` also counts an IP mute on the IP an online player joined from.

## Punish and revoke

```java
xbans.punish(PunishRequest.of(PunishmentType.BAN, cheater.getUniqueId(), "Kill aura")
        .withDuration(Duration.ofDays(30))
        .withStaff(null, "AntiCheat")
        .withSilent(true))
    .thenAccept(ban -> getLogger().info("Banned as #" + ban.id()));
```

A punishment made through the API is recorded, broadcast and enforced like one made by a staff command: the player is kicked or told, and network mode tells the other servers. None of the staff rules apply: a player with `xbans.exempt` or a weight can be punished, for any length, so your plugin decides who may punish whom. The future fails with an `IllegalStateException` saying why if it can't be made (for example, a shorter ban on someone already banned), or a `CancellationException` if a `PunishEvent` listener cancelled it.

```java
xbans.revoke(id, PunishmentType.MUTE, null, "Discord", "Appeal accepted")
    .thenAccept(revoked -> revoked.ifPresent(mute -> getLogger().info("Unmuted #" + mute.id())));
```

`revoke` lifts the player's ban or mute (on every server's scope), or their newest warning, whoever gave it. It's empty if there was nothing to revoke.

## History and alts

```java
xbans.history(id, 1, 10).thenAccept(page -> {
    for (Punishment p : page.punishments()) {
        // newest first; p.removal() is who revoked it, or null
    }
});
xbans.staffHistory(staffId, 1, 10);   // null for the console
xbans.alts(id);                        // accounts that shared an IP
```

The API never shows IP addresses.

## Events

| Event | When | Thread |
| --- | --- | --- |
| `PunishEvent` | A punishment is about to be stored. Cancel it to stop it. Not fired for punishments made on another server. | Off the server's threads (async), before the database is touched: a listener may call the API and wait for it |
| `PunishedEvent` | A punishment was stored. `isFromOtherServer()` for one made elsewhere on the network. | xBans' database thread, or the network thread (async) |
| `RevokedEvent` | A ban, mute or warning was revoked. | The same (async) |

```java
@EventHandler
public void onBan(PunishedEvent event) {
    Punishment p = event.getPunishment();
    if (p.type() == PunishmentType.BAN) {
        discord.post(p.targetName() + " was banned by " + p.staffName() + ": " + p.reason());
    }
}
```

All three are fired asynchronously. Don't touch the world from them directly: schedule onto the right thread.
