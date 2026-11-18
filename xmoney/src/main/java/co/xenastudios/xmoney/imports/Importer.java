package co.xenastudios.xmoney.imports;

import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Ledger;
import co.xenastudios.xmoney.ledger.Outcome;
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
 * (not a number, negative, or past max-balance). Applying it sets each of those accounts'
 * balance to the other plugin's, as an {@code IMPORT} in the ledger, so the previous balance
 * is still in the history.
 */
public final class Importer {

    /** One balance to set. */
    public record Line(UUID id, String name, boolean player, long units) {}

    /** What an import would do. */
    public record Plan(String source, List<Line> lines, int skipped, long total) {}

    private Importer() {}

    public static Plan plan(String source, List<Found> found, int decimals, long max) {
        List<Line> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int skipped = 0;
        long total = 0;
        for (Found one : found) {
            long units;
            try {
                units = Money.units(new BigDecimal(one.balance().strip()), decimals);
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

    /** Sets every balance in the plan. On a network, the future completes once the last is saved. */
    public static CompletableFuture<Integer> apply(Bank bank, Plan plan) {
        Ledger ledger = bank.ledger();
        Cause cause = new Cause(TransactionKind.IMPORT, plan.source(), "");
        List<CompletableFuture<Outcome>> all = new ArrayList<>(plan.lines().size());
        for (Line line : plan.lines()) {
            Account account = bank.accounts().get(line.id());
            if (account == null) {
                account = ledger.account(line.id(), line.name(), line.player());
            } else if (line.name() != null) {
                ledger.rename(account, line.name());
            }
            all.add(ledger.set(account, line.units(), cause));
        }
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            int set = 0;
            for (CompletableFuture<Outcome> one : all) {
                if (one.join().success()) {
                    set++;
                }
            }
            return set;
        });
    }
}
