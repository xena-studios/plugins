package co.xenastudios.xmoney.hook;

import co.xenastudios.xmoney.api.TransactionKind;
import co.xenastudios.xmoney.api.event.BalanceChangedEvent;
import co.xenastudios.xmoney.api.event.PreTransactionEvent;
import co.xenastudios.xmoney.ledger.Cause;
import co.xenastudios.xmoney.ledger.Entry;
import co.xenastudios.xmoney.money.Money;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * The API's events. Each is only built when some plugin listens for it, so a server where
 * nothing does pays a handler-list check per change and nothing more.
 *
 * <p>{@link BalanceChangedEvent}s are fired in the order changes were saved, on a thread of
 * their own (made the first time one is needed), never the writer's: a handler that calls
 * back into xMoney, or is slow, can't hold up saving.
 */
public final class BukkitEvents implements co.xenastudios.xmoney.ledger.Events {

    private final Logger logger;
    private volatile ExecutorService fire;

    public BukkitEvents(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean allow(UUID player, long delta, Cause cause, UUID other) {
        if (cause.kind() == TransactionKind.IMPORT || PreTransactionEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return true;
        }
        PreTransactionEvent event = new PreTransactionEvent(!Bukkit.isPrimaryThread(), player, Money.decimal(delta),
            cause.kind(), cause.source(), cause.reason(), other);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public void saved(List<Entry> entries) {
        if (BalanceChangedEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return;
        }
        executor().execute(() -> {
            for (Entry entry : entries) {
                if (entry.cause().kind() == TransactionKind.IMPORT) {
                    continue;
                }
                try {
                    Bukkit.getPluginManager().callEvent(new BalanceChangedEvent(entry.account().id(), Money.decimal(entry.delta()),
                        Money.decimal(entry.after()), entry.cause().kind(), entry.cause().source(), entry.cause().reason(), entry.other()));
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "A plugin's BalanceChangedEvent handler failed", e);
                }
            }
        });
    }

    private ExecutorService executor() {
        ExecutorService current = fire;
        if (current == null) {
            synchronized (this) {
                if (fire == null) {
                    fire = Executors.newSingleThreadExecutor(work -> {
                        Thread thread = new Thread(work, "xMoney events");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
                current = fire;
            }
        }
        return current;
    }

    /** Stops the events thread, if there is one. */
    public void close() {
        ExecutorService current = fire;
        if (current != null) {
            current.shutdown();
        }
    }
}
