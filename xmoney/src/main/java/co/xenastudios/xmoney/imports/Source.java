package co.xenastudios.xmoney.imports;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Somewhere balances can be imported from. */
public interface Source {

    /** For messages: "EssentialsX". */
    String name();

    /**
     * Every balance it has. Reading never changes the other plugin's data.
     *
     * @return a future completed off the server's threads, or with {@link ImportException}
     *     when there's nothing to read
     */
    CompletableFuture<List<Found>> read();
}
