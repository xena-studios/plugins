package co.xenastudios.xmoney.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.core.db.Database;
import co.xenastudios.xmoney.money.Money;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server killed mid-write (SIGKILL: no shutdown, no flush) never leaves the database
 * disagreeing with itself. A separate JVM pays players back and forth as fast as it can; it's
 * killed while the writer is busy, three times over on the same file, and after each the
 * balances are exactly their ledger rows added up, none is below zero, and the total is what
 * it started as, since only transfers ran.
 */
class KillTest {

    static final int PLAYERS = 30;
    static final long START = 1_000 * Money.ONE;

    @TempDir
    Path folder;

    @Test
    void killedMidWriteTheLedgerAndBalancesAgree() throws Exception {
        Path file = folder.resolve("money.db");
        long saved = 0;
        for (int round = 0; round < 3; round++) {
            Process hammer = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("xmoney.testClasspath"),
                Hammer.class.getName(), file.toString())
                .redirectErrorStream(true)
                .start();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(hammer.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean ready = false;
                long deadline = System.currentTimeMillis() + 60_000;
                while (!ready && (line = out.readLine()) != null && System.currentTimeMillis() < deadline) {
                    ready = line.equals("READY");
                }
                assertTrue(ready, "the hammer never got going");
                Thread.sleep(150 + ThreadLocalRandom.current().nextInt(300));
                hammer.destroyForcibly();
                assertTrue(hammer.waitFor(30, TimeUnit.SECONDS));
            }
            Database database = Bench.sqlite(file);
            Map<UUID, Long> stored = Bench.stored(database);
            assertEquals(PLAYERS, stored.size());
            assertEquals(PLAYERS * START, stored.values().stream().mapToLong(Long::longValue).sum(), "money was made or lost");
            assertTrue(stored.values().stream().allMatch(balance -> balance >= 0), "a balance is below zero");
            Bench.assertLedgerMatchesBalances(database);
            long rows = Bench.ledgerSums(database).size();
            assertTrue(rows > 0);
            saved = Math.max(saved, rows);
        }
    }

    private static String javaCommand() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Runs in its own JVM: makes the players if the file is new, then pays them at random until it's killed. */
    public static final class Hammer {

        public static void main(String[] args) throws Exception {
            Bench bench = new Bench(Bench.sqlite(Path.of(args[0])), false);
            List<Account> players = new ArrayList<>(bench.accounts.all());
            if (players.isEmpty()) {
                for (int i = 0; i < PLAYERS; i++) {
                    players.add(bench.ledger.open(UUID.randomUUID(), "P" + i, START));
                }
            }
            for (int t = 0; t < 8; t++) {
                Thread thread = new Thread(() -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    while (true) {
                        Account from = players.get(random.nextInt(players.size()));
                        Account to = players.get(random.nextInt(players.size()));
                        bench.ledger.transfer(from, to, 1 + random.nextLong(50 * Money.ONE), Bench.CAUSE);
                        if (bench.writer.waiting() > 20_000) {
                            Thread.onSpinWait();
                        }
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
            long before = bench.writer.written();
            while (bench.writer.written() - before < 5_000) {
                Thread.sleep(5);
            }
            System.out.println("READY");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
