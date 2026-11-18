package co.xenastudios.xmoney.hook;

import co.xenastudios.xmoney.Bank;
import co.xenastudios.xmoney.ledger.Account;
import co.xenastudios.xmoney.ledger.Accounts;
import java.util.List;

/** What each of {@link MoneyPlaceholders}' placeholders says. Apart, so it's tested without PlaceholderAPI. */
public final class PlaceholderValues {

    private PlaceholderValues() {}

    /** A placeholder's value, or null when it isn't one of ours. */
    public static String resolve(Bank bank, Account account, String params) {
        long balance = account == null ? 0 : account.balance();
        switch (params) {
            case "balance":
                return bank.format(balance);
            case "balance_raw":
                return bank.format().plain(balance);
            case "balance_short":
                return bank.format().shortForm(balance);
            default:
                break;
        }
        if (!params.startsWith("top_")) {
            return null;
        }
        String[] parts = params.split("_", 3);
        if (parts.length != 3) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (rank < 1 || rank > 100) {
            return "";
        }
        List<Accounts.Ranked> found = bank.accounts().top(rank, 1);
        if (found.isEmpty()) {
            return "";
        }
        Accounts.Ranked entry = found.getFirst();
        return switch (parts[2]) {
            case "name" -> entry.account().name();
            case "balance" -> bank.format(entry.balance());
            case "balance_raw" -> bank.format().plain(entry.balance());
            case "balance_short" -> bank.format().shortForm(entry.balance());
            default -> null;
        };
    }
}
