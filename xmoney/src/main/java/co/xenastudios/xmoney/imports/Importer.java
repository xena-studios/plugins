package co.xenastudios.xmoney.imports;

import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Ledger;
import co.xenastudios.xmoney.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Turns another plugin's balances into a plan, and the plan into balances here. A plan is
 * what a dry run shows: how many balances, their total, and how many can't be imported
 * (not a number, or past max-balance). A negative balance is imported as 0, so it isn't left
 * with a starting balance it never had. Applying it sets each of those accounts' balance to
 * the other plugin's, as an {@code IMPORT} in the ledger, so the previous balance is still in
 * the history. It never renames an account xMoney has, or gives a new one a name another
 * account is known by, so names keep finding the right player.
 */
public final class Importer {

    /** One balance to set. */
    public record Line(UUID id, String name, boolean player, long units) {}

    /** What an import would do. */
    public record Plan(String source, List<Line> lines, int skipped, long total) {}

    private Importer() {}

    public static Plan plan(String source, List<Found> found, long max) {
        List<Line> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int skipped = 0;
        long total = 0;
        for (Found one : found) {
            long units;
            try {
                BigDecimal balance = new BigDecimal(one.balance().strip());
                // Kept to four places, rounded down, like any amount paid in.
                units = Money.incoming(balance.max(BigDecimal.ZERO));
            } catch (NumberFormatException | ArithmeticException e) {
                units = -1;
            }
            if (units < 0 || units > max || !seen.add(one.id())) {
                skipped++;
                continue;
            }
            lines.add(new Line(one.id(), one.name(), one.player(), units));
            total += units;
        }
        return new Plan(source, lines, skipped, total);
    }

    /**
     * Sets every balance in the plan. On a network, a chunk at a time with payments between,
     * and the future completes once the last is saved; nothing is dropped while the database
     * is away.
     *
     * @return how many were set
     */
    public static CompletableFuture<Integer> apply(Bank bank, Plan plan) {
        Ledger ledger = bank.ledger();
        Cause cause = new Cause(TransactionKind.IMPORT, plan.source(), "");
        List<Ledger.Target> targets = new ArrayList<>(plan.lines().size());
        for (Line line : plan.lines()) {
            Account account = bank.accounts().get(line.id());
            if (account == null) {
                account = ledger.account(line.id(), line.name(), line.player());
            }
            targets.add(new Ledger.Target(account, line.units()));
        }
        return ledger.setAll(targets, cause);
    }

    /** How many of the plan's accounts are in {@code changed}: changed here since, so an import would set them back. */
    public static int overwrites(Plan plan, Set<UUID> changed) {
        int count = 0;
        for (Line line : plan.lines()) {
            if (changed.contains(line.id())) {
                count++;
            }
        }
        return count;
    }
}
