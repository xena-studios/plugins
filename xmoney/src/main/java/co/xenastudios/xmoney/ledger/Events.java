package co.xenastudios.xmoney.ledger;

import java.util.List;
import java.util.UUID;

/** Where the ledger tells other plugins about changes: Bukkit events on a server, nothing in tests. */
public interface Events {

    Events NONE = new Events() {};

    /** Before a change, on the caller's thread. False refuses it. */
    default boolean allow(UUID player, long delta, Cause cause, UUID other) {
        return true;
    }

    /** After changes are saved, on the writer's thread. Must return quickly. */
    default void saved(List<Entry> entries) {}
}
