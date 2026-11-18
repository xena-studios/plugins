package co.xenastudios.core.db;

/**
 * For xMoney's tests: runs a change feed's check, which is package-private in the core, the
 * way its repeating task does. In the core's package so it can, and only in tests.
 */
public final class FeedChecks {

    private FeedChecks() {}

    public static void check(ChangeFeed feed) {
        feed.check();
    }
}
