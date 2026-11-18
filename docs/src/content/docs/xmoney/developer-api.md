---
title: Developer API
description: Use xMoney from your own plugin. Payments with a cause in the ledger, balances and the top list from memory, history, and events.
sidebar:
  order: 5
---

This page is for plugin developers. **You may not need it:** xMoney answers Vault and VaultUnlocked, so a plugin written for Vault already works. Use the API for what Vault can't say: why money moved (so `/eco history` shows "MyPlugin: daily reward" rather than "MyPlugin"), why a payment was refused, and the top list.

The API costs nothing unless a plugin uses it: it adds no listeners and no tasks, and its events are only made when something listens for them.

## Add it to your build

The API is `co.xenastudios:xmoney-api`. It's inside the xMoney jar, so don't shade it: depend on it as `compileOnly` (Gradle) or `provided` (Maven).

:::note
The API is published to Maven Central when xMoney 1.0.0 is released. Until then, build it from [the source](https://github.com/xena-studios/plugins) with `./gradlew :xmoney:api:publishToMavenLocal` and add `mavenLocal()` to your repositories.
:::

```kotlin
dependencies {
    compileOnly("co.xenastudios:xmoney-api:1.0.0")
}
```

Then, in `plugin.yml`:

```yaml
softdepend: [xMoney]      # or depend, if your plugin needs it
```

A Paper plugin (`paper-plugin.yml`) lists it under `dependencies.server` with `load: BEFORE` and `join-classpath: true`.

## Get the API

```java
import co.xenastudios.xmoney.api.XMoneyApi;

XMoneyApi money = XMoneyApi.get();
```

`XMoneyApi.get()` throws an `IllegalStateException` if xMoney isn't enabled. It's also registered with Bukkit's services manager, so `Bukkit.getServicesManager().load(XMoneyApi.class)` works too (and returns null instead).

Every method says how it runs. Balances, the top list and the format answer from memory, from any thread, at once. Payments return a `CompletableFuture`: on a single server it's already complete when you get it; on a network a withdrawal completes once the shared database has taken the money, off your thread, or as `UNAVAILABLE` within five seconds if it can't. `history` reads the database and completes off the server's threads. Nothing ever reads the database on your thread.

## Pay and charge

```java
money.deposit(this, player.getUniqueId(), new BigDecimal("50"), "daily reward");

money.withdraw(this, player.getUniqueId(), new BigDecimal("10"), "teleport cost").thenAccept(result -> {
    if (result.success()) {
        // Taken. result.balance() is what they have left.
    } else if (result.status() == TransactionResult.Status.INSUFFICIENT_FUNDS) {
        // They haven't got it.
    }
});

money.transfer(this, buyer, seller, price, "market sale");   // both change, or neither
```

The ledger records each one under your plugin's name, with the reason. Amounts keep four decimal places whatever the currency's decimals, which are only for showing them: paying 0.995 and then 0.005 takes exactly 1. Past four places, deposits are rounded down and withdrawals and transfers up, so rounding never makes money. `result.amount()` is what actually moved, and balances come back with every place they have (`12.50`, `0.995`). A result is never an exception: an invalid amount is `INVALID_AMOUNT`, another plugin refusing is `CANCELLED`, and a network database that's failing or didn't take it up in time is `UNAVAILABLE`: nothing moved, and nothing will later.

## Read

```java
BigDecimal balance = money.balance(uuid);              // 0 without an account
boolean enough = money.has(uuid, new BigDecimal("10"));
List<TopEntry> top = money.top(1, 10);                // ranks 1 to 10
String shown = money.format(new BigDecimal("1234.5")); // "$1,234.50"
money.history(uuid, 1).thenAccept(page -> { /* page.entries(), newest first */ });
```

## Events

| Event | When |
| --- | --- |
| `PreTransactionEvent` | Before a balance changes, on the thread making the change (marked asynchronous when that's not the main thread). Cancel it to refuse the change: a tax, an anti-cheat or a limit plugin. |
| `BalanceChangedEvent` | After a change is saved, off the server's threads, in the order changes were saved. A transfer fires one for each player. |

Neither fires for imports. Both carry the player, the change (negative when money leaves), the kind (`PAY`, `GIVE`, `PLUGIN` and so on), the source (a plugin's or player's name) and the reason.
