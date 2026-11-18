---
title: Developer API
description: Use xRewind from your own plugin. Log changes, look up history, roll back and restore, and hold the writer for a backup. Compared with CoreProtect's API.
sidebar:
  order: 6
---

This page is for plugin developers. xRewind 1.1.0 and newer has an API for other plugins, like CoreProtect's: a plugin that changes blocks can log them, so they show in lookups and can be rolled back, and a plugin can look up the history and roll it back itself, such as a ban plugin undoing a griefer's changes.

The API costs nothing unless a plugin uses it: it adds no listeners and no tasks. Logging goes through the same queue as xRewind's own listeners, and lookups and rollbacks through the same background tasks, per-tick budget and one-at-a-time rule as its commands, so another plugin can't make xRewind heavier than its commands are. Events are only made when something listens for them.

## Add it to your build

The API is `co.xenastudios.xrewind:xrewind-api`. It's inside the xRewind jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

:::note
The API is published to Maven Central when xRewind 1.1.0 is released. Until then, build it from [the source](https://github.com/xena-studios/plugins) with `./gradlew :xrewind:api:publishToMavenLocal` and add `mavenLocal()` to your repositories.
:::

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("co.xenastudios.xrewind:xrewind-api:1.1.0")
}
```

Maven:

```xml
<dependency>
    <groupId>co.xenastudios.xrewind</groupId>
    <artifactId>xrewind-api</artifactId>
    <version>1.1.0</version>
    <scope>provided</scope>
</dependency>
```

Then tell the server your plugin needs xRewind, in `plugin.yml`:

```yaml
depend: [xRewind]      # or softdepend, if your plugin works without it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.xrewind.api.XRewindApi;

XRewindApi xrewind = XRewindApi.get();
```

`XRewindApi.get()` throws an `IllegalStateException` if xRewind isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(XRewindApi.class)` works too (and returns null instead of throwing). With `softdepend`, check that xRewind is enabled before touching any of its classes.

**Threads.** Logging can be called from any thread and returns at once. Everything that reads the database returns a `CompletableFuture` that completes off the server's threads. Never call `join()` or `get()` on one from a server thread: that would freeze the server while the database answers. Use `thenAccept`, and hop back to a server thread (an entity, region or global scheduler) before touching the world.

**Errors.** A mistake in the call, such as a null, a rollback query without a time, or a plan applied twice, throws at once. Anything that depends on xRewind's state at the time fails the future instead: the database not ready yet, another rollback running, a listener cancelling it, or xRewind stopping. Handle those with `exceptionally` or `whenComplete`.

## Log your changes

Changes your plugin makes with `setType` or `setBlockData` aren't events, so xRewind doesn't see them. Log them, and they show in lookups and roll back like any other change.

| Method | Logs |
| --- | --- |
| `logPlacement(actor, location, placed)` | A block placed where there was air. |
| `logRemoval(actor, location, removed)` | A block removed, leaving air. What it held isn't kept: log a chest's items with `logContainerChange` first if they matter. |
| `logChange(actor, location, before, after)` | A block changed from one state to another, such as a plot reset. A change to air is logged as a removal, anything else as a placement. |
| `logContainerChange(actor, location, item, amount)` | Items put into a container (`amount` above 0) or taken out (below 0). |

The actor is a player, `Actor.player(player)`, or a source, `Actor.source("xplots")`, which lookups find as `u:#xplots`. Each call copies what you pass and queues it, about as cheap as xRewind's own listeners. Worlds in `log.excluded-worlds` aren't logged.

```java
Actor plots = Actor.source("xplots");
for (Block block : blocksToReset) {
    BlockData before = block.getBlockData();
    block.setType(Material.AIR);
    xrewind.logChange(plots, block.getLocation(), before, block.getBlockData());
}
```

## Look up the history

Build a `Query` with the same parameters as [`/xr lookup`](/xrewind/commands/):

| Builder | Like |
| --- | --- |
| `users("Steve", "#tnt")`, `excludeUsers(...)` | `u:Steve,#tnt`, `e:` |
| `time(Duration.ofHours(2))` | `t:2h` |
| `radius(location, 20)` | `r:20` |
| `world("world")` | `r:#world w:world` |
| `global()` (the default) | `r:#global` |
| `actions(Action.BREAK, Action.PLACE)` | `a:-block,+block` |
| `blocks(Material.DIAMOND_ORE)`, `excludeBlocks(...)` | `i:diamond_ore`, `e:` |
| `limit(500)` | The most entries to return, 100 unless set (lookups only). |

```java
Query query = Query.builder()
    .users("Steve")
    .time(Duration.ofDays(1))
    .radius(player.getLocation(), 30)
    .build();

xrewind.lookup(query).thenAccept(entries -> {
    for (Entry entry : entries) {
        // entry.time(), entry.actor(), entry.action(), entry.target(), entry.x() ...
    }
});
```

Entries come newest first. A lookup first waits up to a second for what was logged before it to be written, so a change logged a moment ago is usually in it; if the database is slow or failing, it looks anyway, without what isn't written yet. `target()` is the block's state before (with `after()` the state after), a container change's item, a kill's entity type, or a sign, chat message or command's text.

## Roll back and restore

Rolling back is two steps, so you can show what will change first:

1. `planRollback(query)` (or `planRestore(query)`) works out what would change, without changing anything. The query needs a time, as `/xr rollback` does.
2. `apply(plan)` makes the changes, `rollback.ms-per-tick` milliseconds each tick, on the right threads, and completes when it's done.

```java
Query query = Query.builder().users(griefer.getName()).time(Duration.ofDays(3)).global().build();

xrewind.planRollback(query)
    .thenCompose(plan -> {
        getLogger().info("Rolling back " + plan.blocks().size() + " blocks for " + griefer.getName());
        return xrewind.apply(plan);
    })
    .thenAccept(result -> getLogger().info("Done: " + result.applied() + " changed in " + result.took().toSeconds() + "s"));
```

A plan lists its `blocks()` and `items()`, as copies, so you can preview it to a player with `sendBlockChange`. Only one rollback, restore or undo runs at a time, xRewind's commands included: if one is running, `apply` fails with an `IllegalStateException`, and you can try again later. API rollbacks have no radius limit (`rollback.max-radius` is for staff commands), so build the query with care.

A plan is applied once. Applying it again throws an `IllegalStateException`: its changes would be made again, on top of whatever is there by then. To roll back the same thing later, plan again. A plan that wasn't applied, because another rollback was running or a listener cancelled it, can be applied again.

To undo, apply the result's undo plan: `xrewind.apply(result.undo())`. A rollback a player ran with a command keeps its undo for their `/xr undo`, so the undo plan a `RollbackFinishedEvent` shows for it can't be applied.

If a `RollbackEvent` listener cancels a rollback, `apply`'s future fails with a `CancellationException`, and the plan can be applied again, even from the code that handles the failure. A staff member whose rollback, apply or undo another plugin cancelled is told so (`messages.rollback.stopped`).

If xRewind is disabled before a rollback finishes, its future fails with an `IllegalStateException`.

## Hold the writer for a backup

| Method | What it does |
| --- | --- |
| `flush()` | Writes everything logged before the call. Completes with true once that's written, even on a busy server where more keeps coming. A change the database won't take, which xRewind drops with a warning, counts as dealt with. False after 10 seconds, or if the database is down. |
| `pause()` | Stops every write to the database (the log and a purge) until you let go. Completes with a `WriterHold` once whatever was writing has finished its transaction. Changes keep being logged in memory and are written afterwards. |
| `WriterHold.release()` | Lets go. Writing carries on once every plugin's hold is let go. `close()` does the same, for try-with-resources. |

A backup plugin can pause, copy the database, and let go, and never copy it partway through a write. Each `pause()` is its own hold, so two plugins backing up at once don't end each other's. Keep it short: a hold lets go by itself 60 seconds after you get it, with a warning in the console.

Writes are short: the writer and a purge each take a turn per transaction, and a vacuum after a purge hands the space back a few thousand pages a turn. So `pause()` is usually ready at once. It fails:

- with a `TimeoutException` if what's writing doesn't finish within 60 seconds;
- with an `IllegalStateException` if holds that overlap have kept writes stopped for 60 seconds already. New holds are refused until a batch has been written, so backups taking turns can't stop logging for good.

If you cancel the future, or give up on it with `orTimeout`, the hold is let go for you. While a hold is held and 100,000 changes are waiting, the console says a plugin is holding writes, not that the database is slow.

Holds don't apply while xRewind stops: it writes what's waiting (for up to 10 seconds) even while a hold is held, then lets every hold go.

```java
xrewind.pause().thenAccept(hold -> {
    try (hold) {
        copyDatabase();
    }
});
```

## Events

They're in `co.xenastudios.xrewind.api.event`.

| Event | When | Thread |
| --- | --- | --- |
| `RollbackEvent` | A rollback, restore or undo is about to start, from a command or a plugin. Cancellable. Has the plan, and the command's sender (null for a plugin). | A server thread |
| `RollbackFinishedEvent` | It finished. Has the plan, the result and the sender. | The global thread |
| `LookupEvent` | Someone looked up the history: `/xr lookup`, `near`, `page`, the inspector, or a plugin. Has the sender (null for a plugin), the query as it ran (with the sender's position for a radius), and `getPage()`, the page of results it shows. `getQuery().toString()` writes it as lookup parameters. | Async |

For an audit log of who looked up what:

```java
@EventHandler
public void onLookup(LookupEvent event) {
    String who = event.getSender() == null ? "a plugin" : event.getSender().getName();
    auditLog.info(who + " looked up " + event.getQuery());
}
```

## Coming from CoreProtect's API

| CoreProtect | xRewind |
| --- | --- |
| `CoreProtect.getInstance().getAPI()` | `XRewindApi.get()` |
| `logPlacement(user, location, type, blockData)` | `logPlacement(actor, location, blockData)` |
| `logRemoval(user, location, type, blockData)` | `logRemoval(actor, location, blockData)` |
| `logContainerTransaction(user, location)` (call before the change) | `logContainerChange(actor, location, item, amount)` for each item moved |
| User names as strings (`"#xplots"`) | `Actor.player(player)` or `Actor.source("xplots")` |
| `performLookup(time, users, ...)` blocks the calling thread | `lookup(query)` returns a future and never blocks |
| `performRollback(...)` blocks, and runs while you wait | `planRollback(query)`, then `apply(plan)`: a preview first, then a future that completes when it's done |
| `parseResult(String[])` | `Entry`, a record with named fields |
| `isEnabled()`, `APIVersion()` | `XRewindApi.get()` throws if xRewind isn't enabled; the API follows xRewind's version |

## Versions

The API follows xRewind's version: a new minor can add to it, and only a new major can remove anything, after a major of warning. Every type and method says which version added it (`@since`).
