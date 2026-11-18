package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.xmoney.money.Money;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Many threads paying each other at once, as Vault's callers do from every thread: money is
 * never made or lost, no balance goes below zero, and the database ends up agreeing with
 * memory and with its own ledger.
 */
class ConcurrencyTest {

    static final int PLAYERS = 40;
    static final int THREADS = 16;
    static final int CHANGES = 4_000;
    static final long START = 1_000 * Money.ONE;

    @TempDir
    Path folder;

    @Test
    void transfersConserveMoney() throws Exception {
        try (Bench bench = new Bench(Bench.sqlite(folder.resolve("money.db")), false)) {
            List<Account> players = new ArrayList<>();
            for (int i = 0; i < PLAYERS; i++) {
                players.add(bench.ledger.open(UUID.randomUUID(), "P" + i, START));
            }
            // Deposits and withdrawals beside the transfers, counted so the expected total is known.
            AtomicLong added = new AtomicLong();
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> work = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                work.add(pool.submit(() -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < CHANGES; i++) {
                        Account from = players.get(random.nextInt(PLAYERS));
                        Account to = players.get(random.nextInt(PLAYERS));
                        long amount = 1 + random.nextLong(200 * Money.ONE);
                        switch (random.nextInt(10)) {
                            case 0 -> {
                                if (bench.ledger.deposit(to, amount, Bench.CAUSE).join().success()) {
                                    added.addAndGet(amount);
                                }
                            }
                            case 1 -> {
                                if (bench.ledger.withdraw(from, amount, Bench.CAUSE).join().success()) {
                                    added.addAndGet(-amount);
                                }
                            }
                            default -> bench.ledger.transfer(from, to, amount, Bench.CAUSE).join();
                        }
                        if (from.balance() < 0) {
                            throw new AssertionError("a balance went below zero");
                        }
                    }
                }));
            }
            for (Future<?> future : work) {
                future.get(2, TimeUnit.MINUTES);
            }
            pool.shutdown();
            long expected = PLAYERS * START + added.get();
            long inMemory = players.stream().mapToLong(Account::balance).sum();
            assertEquals(expected, inMemory, "money was made or lost in memory");
            assertEquals(expected, bench.accounts.total(), "the running total drifted");
            assertTrue(players.stream().allMatch(player -> player.balance() >= 0));

            bench.flush();
            Map<UUID, Long> stored = Bench.stored(bench.database);
            for (Account player : players) {
                assertEquals(player.balance(), stored.get(player.id()), "the database disagrees with memory for " + player.name());
            }
            Bench.assertLedgerMatchesBalances(bench.database);
        }
    }
}
