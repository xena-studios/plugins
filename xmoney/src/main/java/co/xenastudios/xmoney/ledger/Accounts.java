package co.xenastudios.xmoney.ledger;

import co.xenastudios.xmoney.money.Money;
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
 *
 * <p>A name finds an account only when it's sure whose it is. Two stored accounts with the
 * same name (a name that changed hands, or an old import) leave it out, so callers ask the
 * core's {@code Profiles} instead of guessing; a player joining under a name always takes it.
 */
public final class Accounts {

    /** A place in the top list. Ordered richest first, then by uuid so ties stay put. */
    record Rank(long balance, UUID id) {}

    private static final Comparator<Rank> ORDER = Comparator.comparingLong(Rank::balance).reversed().thenComparing(Rank::id);

    private final ConcurrentHashMap<UUID, Account> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Account> byName = new ConcurrentHashMap<>();
    /** Names more than one stored account has, which find none until a player joins under one. */
    private final java.util.Set<String> ambiguous = ConcurrentHashMap.newKeySet();
    private final ConcurrentSkipListSet<Rank> top = new ConcurrentSkipListSet<>(ORDER);
    // Players' money added up, as whole coins and ten-thousandths apart, so even every player
    // at max-balance can't overflow it.
    private final AtomicLong totalWhole = new AtomicLong();
    private final AtomicLong totalPart = new AtomicLong();

    /** The account, or null. Any thread. */
    public Account get(UUID id) {
        return byId.get(id);
    }

    /** The account known by this name, ignoring case, or null if none is (or more than one may be). Any thread. */
    public Account named(String name) {
        return name.isEmpty() ? null : byName.get(key(name));
    }

    /** Whether more than one account has had this name, so it can't say which is meant. Any thread. */
    public boolean ambiguous(String name) {
        return ambiguous.contains(key(name));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
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
        return create(id, create, false);
    }

    /**
     * Adds an account read from the database, at startup or from another server. If another
     * account has its name, neither is found by it any more.
     */
    Account load(UUID id, String name, boolean player, long balance) {
        return create(id, key -> new Account(key, name, player, balance), true);
    }

    private Account create(UUID id, Function<UUID, Account> create, boolean stored) {
        Account account = byId.get(id);
        if (account != null) {
            return account;
        }
        // Indexed inside, so no one can change it before it's in the top list and the total.
        return byId.computeIfAbsent(id, key -> {
            Account made = create.apply(key);
            index(made, stored);
            return made;
        });
    }

    // A new account's name finds it only if no other account has it. A stored one whose name
    // another has makes the name ambiguous; a new one just isn't found by it.
    private void index(Account account, boolean stored) {
        synchronized (account) {
            String name = account.storedName();
            if (!name.isEmpty()) {
                synchronized (byName) {
                    String key = key(name);
                    if (!ambiguous.contains(key)) {
                        Account other = byName.putIfAbsent(key, account);
                        if (other != null && other != account && stored) {
                            byName.remove(key, other);
                            ambiguous.add(key);
                        }
                    }
                }
            }
            if (account.player()) {
                top.add(new Rank(account.balance, account.id()));
                addTotal(account.balance);
            }
        }
    }

    private void addTotal(long units) {
        totalWhole.addAndGet(units / Money.ONE);
        totalPart.addAndGet(units % Money.ONE);
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
            addTotal(-before);
            addTotal(balance);
        }
    }

    /**
     * A player's name, as they join or as Mojang says it is now: it finds this account from
     * now on, whoever had it before. Any other account that had it loses it (its name is
     * emptied), so once saved, the name can't be ambiguous again after a restart. Only under
     * the account's lock.
     *
     * @return the accounts that lost the name, whose rows need saving
     */
    List<Account> claim(Account account, String name) {
        synchronized (byName) {
            String key = key(name);
            String old = account.storedName();
            if (!old.isEmpty() && !old.equalsIgnoreCase(name)) {
                byName.remove(key(old), account);
            }
            List<Account> released = new ArrayList<>(1);
            Account holder = byName.get(key);
            if (holder != null && holder != account) {
                released.add(holder);
            }
            if (ambiguous.remove(key)) {
                // Rare (a name that changed hands while both were away), so a look through every account is fine.
                for (Account other : byId.values()) {
                    if (other != account && other != holder && name.equalsIgnoreCase(other.storedName())) {
                        released.add(other);
                    }
                }
            }
            for (Account other : released) {
                other.name("");
            }
            account.name(name);
            byName.put(key, account);
            return released;
        }
    }

    /** An account's name is gone (another server gave it to a player): it isn't found by it any more. */
    void forget(Account account) {
        synchronized (byName) {
            String old = account.storedName();
            if (!old.isEmpty()) {
                byName.remove(key(old), account);
                account.name("");
            }
        }
    }

    /**
     * A new name for an account, unless another account is known by it (or more than one has
     * had it). Only under the account's lock.
     *
     * @return whether it was renamed
     */
    boolean rename(Account account, String name) {
        synchronized (byName) {
            String key = key(name);
            Account other = byName.get(key);
            if (other != null && other != account || ambiguous.contains(key)) {
                return false;
            }
            String old = account.storedName();
            if (!old.isEmpty() && !old.equalsIgnoreCase(name)) {
                byName.remove(key(old), account);
            }
            account.name(name);
            byName.put(key, account);
            return true;
        }
    }

    /**
     * A name another server saved for an account. A player's takes the name, since it came
     * from them joining there; any other account's only if it's free. Only under the
     * account's lock.
     */
    void renamedElsewhere(Account account, String name) {
        if (name.isEmpty()) {
            forget(account);
        } else if (account.player()) {
            claim(account, name);
        } else {
            rename(account, name);
        }
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

    /** Every player's balance added up, in units, or {@link Long#MAX_VALUE} if that's more than a long holds. */
    public long total() {
        long whole = totalWhole.get();
        long part = totalPart.get();
        if (whole > (Long.MAX_VALUE - Math.max(0, part)) / Money.ONE) {
            return Long.MAX_VALUE;
        }
        return whole * Money.ONE + part;
    }

    /** Every account, for imports and tests. Weakly consistent. */
    public Collection<Account> all() {
        return byId.values();
    }
}
