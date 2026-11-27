package co.xenastudios.xmarket.price

import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.ShopEntry
import co.xenastudios.xmarket.api.TradeKind

/** How long a limit counts before it starts again: a day and a week by the server's calendar (`limits.timezone`), or ever. */
public enum class Period(public val word: String) {
    DAY("day"),
    WEEK("week"),
    EVER("ever"),
    ;

    public companion object {
        public fun of(word: String): Period? = entries.firstOrNull { it.word == word.trim().lowercase() }
    }
}

/** At most [amount] bought (or sold) per [period]: by each player, or by the whole server ([server]). */
public class Limit(public val kind: TradeKind, public val amount: Int, public val period: Period, public val server: Boolean) {
    override fun toString(): String = "${if (server) "server" else "player"} ${kind.name.lowercase()} $amount per ${period.word}"
}

/**
 * A condition through placeholders, as `%player_level% >= 10`: the placeholder's value for the
 * player compared with [value], as numbers when both are, as text otherwise.
 */
public class Requirement(public val placeholder: String, public val op: String, public val value: String) {
    public fun test(actual: String?): Boolean {
        if (actual == null) return false
        val a = actual.trim().replace(",", "").toDoubleOrNull()
        val b = value.trim().toDoubleOrNull()
        if (a != null && b != null) {
            return when (op) {
                ">=" -> a >= b
                ">" -> a > b
                "<=" -> a <= b
                "<" -> a < b
                "!=" -> a != b
                else -> a == b
            }
        }
        val same = actual.trim().equals(value.trim(), ignoreCase = true)
        return if (op == "!=") !same else op == "==" && same
    }

    override fun toString(): String = "$placeholder $op $value"

    public companion object {
        private val FORM = Regex("""^\s*(%[^%\s]+%)\s*(>=|<=|==|!=|>|<|=)\s*(.+?)\s*$""")

        /** `%player_level% >= 10`, or `null` if it isn't one. */
        public fun parse(text: String): Requirement? {
            val m = FORM.matchEntire(text) ?: return null
            val op = m.groupValues[2].let { if (it == "=") "==" else it }
            return Requirement(m.groupValues[1], op, m.groupValues[3].trim('"', '\''))
        }
    }
}

/** What a category or an entry is paid in. */
public sealed class Currency(public val id: String) {
    /** Money, through xMoney, Vault's economy or the Common Economy API. */
    public object Money : Currency("money")

    /** Experience levels, whole ones. */
    public object Xp : Currency("xp")

    /** An item, whole ones: diamonds, gold ingots. Needs no economy at all. */
    public class Items(public val item: ItemSpec) : Currency("item:" + item.id) {
        override fun equals(other: Any?): Boolean = other is Items && other.item == item
        override fun hashCode(): Int = item.hashCode()
    }

    /** Whether it pays in whole numbers only: items and levels. */
    public val whole: Boolean get() = this !is Money

    override fun toString(): String = id

    public companion object {
        /** `money`, `xp`, or `item:diamond`; `null` if it isn't one. */
        public fun of(text: String): Currency? {
            val t = text.trim().lowercase()
            return when {
                t == "money" || t.isEmpty() -> Money
                t == "xp" || t == "levels" || t == "experience" -> Xp
                t.startsWith("item:") -> Items(ItemSpec.ofKey(t.removePrefix("item:")))
                else -> null
            }
        }
    }
}

/** One thing a category sells or buys: its item, its prices for one, and its rules. */
public class Entry(
    /** Its id in its category: the item's key, or an owner's own name. */
    public val id: String,
    public val item: ItemSpec,
    public val buy: Money?,
    public val sell: Money?,
    /** How many one purchase or sale of it is: 16 for a bundle of arrows. Prices are for one of these. */
    public val amount: Int = 1,
    /** The menu's name for it; `null` keeps the item's own. Markup. */
    public val name: String? = null,
    public val lore: List<String> = emptyList(),
    /** Commands run (as the console) for each one bought, with `{player}` and `{uuid}`; then the item isn't given. */
    public val commands: List<String> = emptyList(),
    /** The icon for a command entry; [item] otherwise. */
    public val icon: ItemSpec? = null,
    public val permission: String? = null,
    public val limits: List<Limit> = emptyList(),
    public val requires: List<Requirement> = emptyList(),
    /** Where it sits in its category's pages, or -1 to follow the one before. */
    public val slot: Int = -1,
) {
    public fun price(kind: TradeKind): Money? = if (kind == TradeKind.BUY) buy else sell

    /** Its entry with these prices: for /shop edit. */
    public fun withPrices(buy: Money?, sell: Money?): Entry =
        Entry(id, item, buy, sell, amount, name, lore, commands, icon, permission, limits, requires, slot)

    public fun withId(id: String): Entry = Entry(id, item, buy, sell, amount, name, lore, commands, icon, permission, limits, requires, -1)
}

/** One `shops/<id>.yml`: a page of the shop. */
public class Category(
    public val id: String,
    /** Markup, as the menu titles it. */
    public val name: String,
    public val icon: ItemSpec,
    /** Its slot in `/shop`'s menu, or -1 for the next free one. */
    public val slot: Int,
    public val currency: Currency,
    public val permission: String?,
    public val requires: List<Requirement>,
    public val entries: List<Entry>,
    /** What its file says about it, kept when the file is written again. */
    public val description: String? = null,
) {
    public fun entry(id: String): Entry? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

    public fun withEntries(entries: List<Entry>): Category = Category(id, name, icon, slot, currency, permission, requires, entries, description)
}

/**
 * The whole price list, as loaded: every category, and an index of every entry by item, so a
 * price is a lookup. Prices here are after the `multiplier`. Read-only; a reload or an edit
 * makes a new one.
 */
public class PriceList(public val categories: List<Category>, multiplier: Long = 100) {
    /** An entry with its category: what a trade needs. */
    public class Placed(public val category: Category, public val entry: Entry) {
        public val currency: Currency get() = category.currency
        public val api: ShopEntry
            get() = ShopEntry(category.id, entry.id, entry.item.toKey(), entry.buy, entry.sell, category.currency.id, entry.commands.isNotEmpty())
    }

    /** The categories after the multiplier. */
    public val scaled: List<Category> = if (multiplier == 100L) categories else categories.map { c ->
        c.withEntries(c.entries.map { it.withPrices(it.buy?.percentUp(multiplier), it.sell?.percent(multiplier)) })
    }

    // By item id, the most specific first (full data, then a potion or enchantments, then plain).
    private val byId: Map<String, List<Placed>> = index(scaled)

    private val byCategory: Map<String, Category> = scaled.associateBy { it.id.lowercase() }

    /** Every item id something here sells or buys. */
    public val ids: Set<String> get() = byId.keys

    /** Whether some entry for [id] needs an item's full data to match. */
    public fun wantsData(id: String): Boolean = byId[id]?.any { it.entry.item.data != null } == true

    /** The entry that buys or sells [item] for [kind]: the most specific that matches and has that price. */
    public fun find(item: ItemKey, kind: TradeKind): Placed? =
        byId[item.id]?.firstOrNull { it.entry.item.matches(item) && it.entry.price(kind) != null }

    /** The entry [item] is, whatever its prices. */
    public fun find(item: ItemKey): Placed? = byId[item.id]?.firstOrNull { it.entry.item.matches(item) }

    public fun category(id: String): Category? = byCategory[id.lowercase()]

    public fun entry(category: String, id: String): Placed? {
        val c = category(category) ?: return null
        val e = c.entry(id) ?: return null
        return Placed(c, e)
    }

    /** Every entry, category by category. */
    public fun all(): List<Placed> = scaled.flatMap { c -> c.entries.map { Placed(c, it) } }

    public companion object {
        public val EMPTY: PriceList = PriceList(emptyList())

        private fun index(categories: List<Category>): Map<String, List<Placed>> {
            val out = HashMap<String, MutableList<Placed>>()
            for (specific in 2 downTo 0) {
                for (category in categories) for (entry in category.entries) {
                    if (entry.commands.isNotEmpty() || entry.item.specific != specific) continue
                    val list = out[entry.item.id] ?: ArrayList<Placed>().also { out[entry.item.id] = it }
                    list += Placed(category, entry)
                }
            }
            return out
        }
    }
}
