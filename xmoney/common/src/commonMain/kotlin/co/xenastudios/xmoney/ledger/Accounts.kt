package co.xenastudios.xmoney.ledger

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.xmoney.api.Amount

/**
 * Every account, in memory: by uuid, by name, and sorted by balance for the top list. A few
 * hundred bytes an account, so 100,000 players are a few tens of MB at most.
 *
 * A name finds an account only when it's sure whose it is. Two stored accounts with the same
 * name (a name that changed hands, or an old import) leave it out, so callers ask the server's
 * player cache instead of guessing; a player joining under a name always takes it.
 */
public class Accounts {
    private val byId = concurrentMap<PlayerRef, Account>()
    private val byName = concurrentMap<String, Account>()
    /** Names more than one stored account has, which find none until a player joins under one. */
    private val ambiguous = concurrentMap<String, Unit>()
    private val names = Any()
    private val ranking = newRanking()
    private val totals = Any()
    // Players' money added up, as whole coins and ten-thousandths apart, so even every player at
    // max-balance can't overflow it.
    private var totalWhole = 0L
    private var totalPart = 0L

    /** The account, or `null`. Any thread. */
    public operator fun get(id: PlayerRef): Account? = byId[id]

    /** The account known by this name, ignoring case, or `null` if none is (or more than one may be). Any thread. */
    public fun named(name: String): Account? = if (name.isEmpty()) null else byName[key(name)]

    /** Whether more than one account has had this name, so it can't say which is meant. Any thread. */
    public fun ambiguous(name: String): Boolean = key(name) in ambiguous

    /**
     * The account, made by [create] if there's none. [create] runs at most once per uuid (under
     * the index's lock), and nobody sees the account until it returns, so it can queue the
     * account's first write before anything else can change it.
     */
    internal inline fun getOrCreate(id: PlayerRef, create: () -> Account): Account {
        byId[id]?.let { return it }
        return locked(names) {
            byId[id] ?: create().also { index(it, stored = false) }
        }
    }

    /** Adds an account read from the database, at startup or from another server. If another account has its name, neither is found by it. */
    internal fun load(id: PlayerRef, name: String, player: Boolean, balance: Long): Account {
        byId[id]?.let { return it }
        return locked(names) {
            byId[id] ?: Account(id, name, player, balance).also { index(it, stored = true) }
        }
    }

    // Under the names lock. A new account's name finds it only if no other account has it; a
    // stored one whose name another has makes the name ambiguous.
    private fun index(account: Account, stored: Boolean) {
        val name = account.storedName
        if (name.isNotEmpty()) {
            val key = key(name)
            if (key !in ambiguous) {
                val other = byName[key]
                if (other == null) byName[key] = account
                else if (other !== account && stored) {
                    byName.remove(key)
                    ambiguous[key] = Unit
                }
            }
        }
        byId[account.id] = account
        if (account.player) {
            ranking.add(account.balance, account.id)
            addTotal(account.balance)
        }
    }

    private fun addTotal(units: Long) = locked(totals) {
        totalWhole += units / Amount.ONE
        totalPart += units % Amount.ONE
    }

    /** Sets a balance. Only under the account's lock. */
    internal fun set(account: Account, balance: Long) {
        val before = account.balance
        if (before == balance) return
        account.balance = balance
        if (account.player && !account.gone) {
            ranking.remove(before, account.id)
            ranking.add(balance, account.id)
            addTotal(balance - before)
        }
    }

    /**
     * A player's name, as they join or as the server says it is now: it finds this account from
     * now on, whoever had it before. Any other account that had it loses it (its name is emptied),
     * so once saved the name can't be ambiguous again after a restart.
     *
     * @return the accounts that lost the name, whose rows need saving
     */
    internal fun claim(account: Account, name: String): List<Account> = locked(names) {
        val key = key(name)
        val old = account.storedName
        if (old.isNotEmpty() && !old.equals(name, ignoreCase = true) && byName[key(old)] === account) byName.remove(key(old))
        val released = ArrayList<Account>(1)
        val holder = byName[key]
        if (holder != null && holder !== account) released += holder
        if (ambiguous.remove(key) != null) {
            // Rare (a name that changed hands while both were away), so a look through every account is fine.
            for (other in byId.values) {
                if (other !== account && other !== holder && name.equals(other.storedName, ignoreCase = true)) released += other
            }
        }
        for (other in released) other.storedName = ""
        account.storedName = name
        byName[key] = account
        released
    }

    /** An account's name is gone (another server gave it to a player): it isn't found by it any more. */
    internal fun forgetName(account: Account) = locked(names) {
        val old = account.storedName
        if (old.isNotEmpty()) {
            if (byName[key(old)] === account) byName.remove(key(old))
            account.storedName = ""
        }
    }

    /** A new name for an account, unless another account is known by it (or more than one has had it). */
    internal fun rename(account: Account, name: String): Boolean = locked(names) {
        val key = key(name)
        val other = byName[key]
        if ((other != null && other !== account) || key in ambiguous) return@locked false
        val old = account.storedName
        if (old.isNotEmpty() && !old.equals(name, ignoreCase = true) && byName[key(old)] === account) byName.remove(key(old))
        account.storedName = name
        byName[key] = account
        true
    }

    /**
     * A name another server saved for an account. A player's takes the name, since it came from
     * them joining there; any other account's only if it's free.
     */
    internal fun renamedElsewhere(account: Account, name: String) {
        when {
            name.isEmpty() -> forgetName(account)
            account.player -> claim(account, name)
            else -> rename(account, name)
        }
    }

    /** Takes an account out entirely: `/xmoney forget`, or another server forgot it. Under its lock. */
    internal fun remove(account: Account) {
        locked(names) {
            if (byId[account.id] !== account) return
            byId.remove(account.id)
            val name = account.storedName
            if (name.isNotEmpty() && byName[key(name)] === account) byName.remove(key(name))
        }
        if (account.player && !account.gone) {
            ranking.remove(account.balance, account.id)
            addTotal(-account.balance)
        }
        account.gone = true
    }

    /** One place in the top list. */
    public class Ranked(public val rank: Int, public val account: Account, public val balance: Long)

    /** [count] accounts from rank [from] (1 is the richest), with the balance each had. */
    public fun top(from: Int, count: Int): List<Ranked> {
        val out = ArrayList<Ranked>(minOf(count, 100))
        var rank = from
        for ((balance, id) in ranking.top(from, count)) {
            val account = byId[id]
            if (account != null) out += Ranked(rank, account, balance)
            rank++
        }
        return out
    }

    /** How many players are in the top list. */
    public val ranked: Int get() = ranking.size

    /** How many accounts there are. */
    public val size: Int get() = byId.size

    /** Every player's balance added up, in units, or [Long.MAX_VALUE] if that's more than a long holds. */
    public fun total(): Long = locked(totals) {
        if (totalWhole > (Long.MAX_VALUE - maxOf(0, totalPart)) / Amount.ONE) Long.MAX_VALUE else totalWhole * Amount.ONE + totalPart
    }

    /** Every account, for imports and tests. Weakly consistent. */
    public fun all(): Collection<Account> = byId.values

    private fun key(name: String) = name.lowercase()
}
