package co.xenastudios.xmoney.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Every account, in memory: by uuid, by name, and sorted by balance for the top list.
 * A few hundred bytes an account, so 100,000 players are a few tens of MB at most.
 */
public final class Accounts {

    /** A place in the top list. Ordered richest first, then by uuid so ties stay put. */
    record Rank(long balance, UUID id) {}

    private static final Comparator<Rank> ORDER = Comparator.comparingLong(Rank::balance).reversed().thenComparing(Rank::id);

    private final ConcurrentHashMap<UUID, Account> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Account> byName = new ConcurrentHashMap<>();
    private final ConcurrentSkipListSet<Rank> top = new ConcurrentSkipListSet<>(ORDER);
    private final AtomicLong total = new AtomicLong();

    /** The account, or null. Any thread. */
    public Account get(UUID id) {
        return byId.get(id);
    }

    /** The account last known by this name, ignoring case, or null. Any thread. */
    public Account named(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The account, made by {@code create} if there's none. {@code create} runs at most once
     * per uuid, and nobody sees the account until it returns, so it can queue the account's
     * first write before anything else can change it.
     */
    Account getOrCreate(UUID id, Function<UUID, Account> create) {
        Account account = byId.get(id);
        if (account != null) {
            return account;
        }
        // Indexed inside, so no one can change it before it's in the top list and the total.
        return byId.computeIfAbsent(id, key -> {
            Account made = create.apply(key);
            index(made);
            return made;
        });
    }

    /** Adds an account read from the database, at startup or from another server. */
    Account load(UUID id, String name, boolean player, long balance) {
        return getOrCreate(id, key -> new Account(key, name, player, balance));
    }

    private void index(Account account) {
        synchronized (account) {
            if (account.name() != null && !account.name().isEmpty()) {
                byName.put(account.name().toLowerCase(Locale.ROOT), account);
            }
            if (account.player()) {
                top.add(new Rank(account.balance, account.id()));
            }
            total.addAndGet(account.balance);
        }
    }

    /** Sets a balance. Only under the account's lock. */
    void set(Account account, long balance) {
        long before = account.balance;
        if (before == balance) {
            return;
        }
        account.balance = balance;
        if (account.player()) {
            top.remove(new Rank(before, account.id()));
            top.add(new Rank(balance, account.id()));
        }
        total.addAndGet(balance - before);
    }

    /** A new name for an account, as when a player joins after changing theirs. */
    void rename(Account account, String name) {
        String old = account.name();
        if (name.equals(old)) {
            return;
        }
        account.name(name);
        if (old != null) {
            byName.remove(old.toLowerCase(Locale.ROOT), account);
        }
        byName.put(name.toLowerCase(Locale.ROOT), account);
    }

    /** {@code count} accounts from rank {@code from} (1 is the richest), with the balance each had. */
    public List<Ranked> top(int from, int count) {
        List<Ranked> out = new ArrayList<>(Math.min(count, 100));
        int rank = 0;
        Iterator<Rank> it = top.iterator();
        while (it.hasNext() && out.size() < count) {
            Rank entry = it.next();
            rank++;
            if (rank < from) {
                continue;
            }
            Account account = byId.get(entry.id());
            if (account != null) {
                out.add(new Ranked(rank, account, entry.balance()));
            }
        }
        return out;
    }

    /** One place in the top list. */
    public record Ranked(int rank, Account account, long balance) {}

    /** How many players are in the top list. */
    public int ranked() {
        return top.size();
    }

    /** How many accounts there are. */
    public int size() {
        return byId.size();
    }

    /** Every balance added up, in units. */
    public long total() {
        return total.get();
    }

    /** Every account, for imports and tests. Weakly consistent. */
    public Collection<Account> all() {
        return byId.values();
    }
}
