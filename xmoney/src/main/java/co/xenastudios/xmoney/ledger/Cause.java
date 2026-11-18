package co.xenastudios.xmoney.ledger;

import co.xenastudios.xmoney.api.TransactionKind;

/**
 * Why money moved, as the ledger records it.
 *
 * @param source a plugin's name, a player's, or "Console"
 * @param reason a few words, or empty
 */
public record Cause(TransactionKind kind, String source, String reason) {

    public Cause {
        source = clip(source == null || source.isBlank() ? "Unknown" : source, Schema.SOURCE_LENGTH);
        reason = clip(reason == null ? "" : reason, Schema.REASON_LENGTH);
    }

    private static String clip(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }
}
