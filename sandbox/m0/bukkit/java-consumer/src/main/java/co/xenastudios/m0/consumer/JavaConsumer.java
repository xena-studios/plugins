package co.xenastudios.m0.consumer;

import co.xenastudios.m0.api.Account;
import co.xenastudios.m0.api.MemoryWallet;
import co.xenastudios.m0.api.Outcome;
import co.xenastudios.m0.api.Pitfalls;
import co.xenastudios.m0.api.Subscription;
import co.xenastudios.m0.api.Transaction;
import co.xenastudios.m0.api.Transfer;
import co.xenastudios.m0.api.Wallet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;

/** A Java plugin's view of the Kotlin API, compiled with -Xlint:all -Werror. */
public final class JavaConsumer {

    private JavaConsumer() {}

    public static void main(String[] args) throws Exception {
        Wallet.register(new MemoryWallet());

        // The accessor: a static method, as if Wallet were written in Java.
        Wallet wallet = Wallet.api();
        UUID steve = UUID.randomUUID();

        // An event listener as a plain Java lambda (java.util.function.Consumer).
        AtomicLong seen = new AtomicLong();
        Subscription subscription = wallet.onChange(event -> {
            seen.addAndGet(event.getDifference());
            System.out.println("event: " + event.getBefore() + " -> " + event.getAfter() + ", player " + event.isPlayer());
        });

        // CompletableFuture returns, chained the Java way; the two-argument overload is the default method.
        Transaction first = wallet.deposit(steve, 1_000L).get();
        Transaction second = wallet.deposit(steve, 500L, "vote").thenApply(t -> t).join();
        System.out.println("deposits: " + first + " / " + second.getReason() + " -> " + second.getAfter());
        subscription.cancel();

        // A data class from Java: getters, equals, copy with every argument.
        Transaction copy = second.copy(second.getPlayer(), 0L, 1L, "copied");
        System.out.println("copy: " + copy + ", equals itself: " + copy.equals(copy));

        // A default argument on an interface method isn't an overload for Java: top() needs its argument.
        List<Account> top = wallet.top(10);
        System.out.println("top: " + top);
        try {
            top.add(new Account(steve, "x", 1L));
            System.out.println("top list accepted an add");
        } catch (UnsupportedOperationException e) {
            System.out.println("top list is read-only at runtime: " + e.getClass().getSimpleName());
        }

        // @JvmOverloads on a constructor: every prefix works.
        Transfer transfer = new Transfer(steve, UUID.randomUUID(), 5L);
        Transfer quiet = new Transfer(steve, UUID.randomUUID(), 5L, "quiet", false);
        System.out.println("transfers: " + transfer.getReason() + "/" + transfer.getNotify() + ", " + quiet.getReason() + "/" + quiet.getNotify());

        // A sealed interface with a data object: instanceof patterns (Java 17).
        Outcome outcome = new Outcome.Refused("too poor");
        if (outcome instanceof Outcome.Refused refused) {
            System.out.println("refused: " + refused.getReason());
        } else if (outcome == Outcome.Done.INSTANCE) {
            System.out.println("done");
        }

        // Null where Kotlin says non-null: a clear NullPointerException, from the Kotlin side.
        try {
            wallet.balance(null);
        } catch (NullPointerException e) {
            System.out.println("null check: " + e.getMessage());
        }

        // ---- the pitfalls, as Java has to write them ----
        // An object without @JvmStatic goes through INSTANCE.
        System.out.println("object: " + Pitfalls.INSTANCE.plain());
        // A default argument without @JvmOverloads: both arguments.
        System.out.println("greet: " + Pitfalls.INSTANCE.greet("Steve", "Hello"));
        // A Kotlin function type: the lambda has to return Unit.INSTANCE.
        Pitfalls.INSTANCE.listen(event -> {
            System.out.println("kotlin lambda: " + event.getAfter());
            return Unit.INSTANCE;
        });
        // No @Throws: catching IOException doesn't compile, so it's caught as Exception.
        try {
            Pitfalls.INSTANCE.read();
        } catch (Exception e) {
            System.out.println("undeclared checked exception: " + e);
        }
        // Pitfalls.INSTANCE.pay(steve, ...) can't be written at all: the value class mangles its name.
        System.out.println("events summed: " + seen.get());
    }
}
