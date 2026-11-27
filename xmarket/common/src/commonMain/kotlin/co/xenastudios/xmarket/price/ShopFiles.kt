package co.xenastudios.xmarket.price

import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.TradeKind

/**
 * A category's file, `shops/<id>.yml`, both ways: read from the YAML parser's tree, every
 * problem said in English with its line, and written back as the owner would write it, one
 * line per plain item:
 *
 * ```yaml
 * name: "<aqua>Ores & Minerals"
 * icon: diamond
 * slot: 11
 * currency: money
 * items:
 *   diamond: {buy: 200, sell: 60}
 *   vip:
 *     icon: nether_star
 *     buy: 5000
 *     commands: ["lp user {player} parent add vip"]
 * ```
 */
public object ShopFiles {
    /** What reading a file found: the category, and each problem in it. */
    public class Read(public val category: Category?, public val problems: List<String>)

    public fun read(id: String, root: YamlNode?, defaultCurrency: Currency = Currency.Money): Read {
        val problems = ArrayList<String>()
        val map = root as? YamlNode.Mapping ?: return Read(null, listOf("shops/$id.yml isn't a mapping of settings, so the category is left out."))
        val at = At(id, map.line)
        val name = text(map["name"]) ?: id.replaceFirstChar { it.uppercase() }
        val icon = text(map["icon"])?.let(ItemSpec::ofKey) ?: ItemSpec("minecraft:chest")
        val slot = int(map["slot"]) ?: -1
        val currencyText = text(map["currency"])
        val currency = if (currencyText == null) defaultCurrency else Currency.of(currencyText) ?: defaultCurrency.also { problems += "${at.of(map["currency"])}: currency is money, xp or item:<id>, not $currencyText; using money." }
        val permission = text(map["permission"])?.takeIf { it.isNotBlank() }
        val requires = requirements(map["requires"], problems, at)
        val entries = ArrayList<Entry>()
        val items = map["items"]
        if (items != null && items !is YamlNode.Mapping) problems += "${at.of(items)}: items is a mapping of entries."
        val seen = HashSet<String>()
        for (e in (items as? YamlNode.Mapping)?.entries.orEmpty()) {
            if (!seen.add(e.key.lowercase())) {
                problems += "${at.of(e.value)}: ${e.key} is in the category twice; the first is kept."
                continue
            }
            entry(e.key, e.value, problems, at)?.let { entries += it }
        }
        val description = text(map["description"])
        return Read(Category(id, name, icon, slot, currency, permission, requires, entries, description), problems)
    }

    private fun entry(key: String, node: YamlNode, problems: MutableList<String>, where: At): Entry? {
        // A bare number is a buy price; a mapping says the rest.
        if (node is YamlNode.Scalar) {
            val buy = Money.parse(node.content) ?: return null.also { problems += "${where.of(node)}: $key is a price or a mapping, not ${node.content}." }
            return Entry(key, ItemSpec.ofKey(itemKey(key)), buy, null)
        }
        val m = node as? YamlNode.Mapping ?: return null.also { problems += "${where.of(node)}: $key isn't a mapping." }
        val item = ItemSpec.ofKey(text(m["item"]) ?: itemKey(key))
        val data = text(m["data"])
        val spec = if (data != null) ItemSpec(item.id, item.potion, item.enchantments, data) else item
        val buy = price(m["buy"], "buy", key, problems, where)
        val sell = price(m["sell"], "sell", key, problems, where)
        val amount = (int(m["amount"]) ?: 1).coerceIn(1, 64 * 36)
        val commands = list(m["commands"])
        if (buy == null && sell == null && commands.isEmpty()) problems += "${where.of(m)}: $key has no buy or sell price, so it's shown but can't be traded."
        if (commands.isNotEmpty() && sell != null) problems += "${where.of(m["sell"])}: $key runs commands, which can't be sold back; its sell price is ignored."
        val limits = ArrayList<Limit>()
        limit(m["limit"], server = false, limits, key, problems, where)
        limit(m["server-limit"], server = true, limits, key, problems, where)
        return Entry(
            key, spec, buy, if (commands.isEmpty()) sell else null, amount,
            name = text(m["name"]), lore = list(m["lore"]), commands = commands,
            icon = text(m["icon"])?.let(ItemSpec::ofKey),
            permission = text(m["permission"])?.takeIf { it.isNotBlank() },
            limits = limits, requires = requirements(m["requires"], problems, where),
            slot = int(m["slot"]) ?: -1,
        )
    }

    // Where in a file a problem is, for its message.
    internal class At(private val id: String, private val line: Int) {
        fun of(node: YamlNode?): String = "shops/$id.yml line ${(node?.line ?: line) + 1}"
    }

    // An entry's key names its item unless it says otherwise: `diamond`, `potion{swiftness}`.
    private fun itemKey(key: String): String = key

    private fun price(node: YamlNode?, what: String, key: String, problems: MutableList<String>, where: At): Money? {
        val t = text(node) ?: return null
        if (t.equals("no", true) || t.equals("false", true) || t == "-") return null
        val m = Money.parse(t)
        if (m == null || m.isNegative) {
            problems += "${where.of(node)}: $key's $what price is a number of 0 or more, not $t; it can't be ${if (what == "buy") "bought" else "sold"}."
            return null
        }
        return m
    }

    private fun limit(node: YamlNode?, server: Boolean, into: MutableList<Limit>, key: String, problems: MutableList<String>, where: At) {
        val m = node as? YamlNode.Mapping ?: run {
            if (node != null) problems += "${where.of(node)}: $key's ${if (server) "server-limit" else "limit"} is a mapping, such as {buy: 16, per: day}."
            return
        }
        val periodText = text(m["per"]) ?: "day"
        val period = Period.of(periodText) ?: Period.DAY.also { problems += "${where.of(m["per"])}: per is day, week or ever, not $periodText; using day." }
        for ((field, kind) in listOf("buy" to TradeKind.BUY, "sell" to TradeKind.SELL)) {
            val n = int(m[field]) ?: continue
            if (n < 0) problems += "${where.of(m[field])}: a limit is 0 or more." else into += Limit(kind, n, period, server)
        }
    }

    private fun requirements(node: YamlNode?, problems: MutableList<String>, where: At): List<Requirement> {
        val out = ArrayList<Requirement>()
        for (text in list(node)) {
            val r = Requirement.parse(text)
            if (r == null) problems += "${where.of(node)}: a requirement is like \"%player_level% >= 10\", not $text; it's left out." else out += r
        }
        return out
    }

    internal fun text(node: YamlNode?): String? = (node as? YamlNode.Scalar)?.content

    private fun int(node: YamlNode?): Int? = text(node)?.trim()?.toIntOrNull()

    private fun list(node: YamlNode?): List<String> = when (node) {
        is YamlNode.Sequence -> node.items.mapNotNull(::text)
        is YamlNode.Scalar -> listOf(node.content)
        else -> emptyList()
    }

    /** [category] as its file: what [read] reads back to the same category. */
    public fun write(category: Category, header: List<String> = emptyList()): String = buildString {
        for (line in header) append(if (line.isEmpty()) "#\n" else "# $line\n")
        category.description?.let { append("description: ").append(quote(it)).append('\n') }
        append("name: ").append(quote(category.name)).append('\n')
        append("icon: ").append(quote(category.icon.key)).append('\n')
        if (category.slot >= 0) append("slot: ").append(category.slot).append('\n')
        append("currency: ").append(quote(currencyText(category.currency))).append('\n')
        category.permission?.let { append("permission: ").append(quote(it)).append('\n') }
        if (category.requires.isNotEmpty()) append("requires: ").append(flowList(category.requires.map { it.toString() })).append('\n')
        append("items:\n")
        for (e in category.entries) {
            val simple = e.item.data == null && e.item.key == e.id && e.name == null && e.lore.isEmpty() && e.commands.isEmpty() &&
                e.icon == null && e.permission == null && e.limits.isEmpty() && e.requires.isEmpty() && e.slot < 0 && e.amount == 1
            append("  ").append(quote(e.id)).append(':')
            if (simple) {
                val parts = ArrayList<String>(2)
                e.buy?.let { parts += "buy: $it" }
                e.sell?.let { parts += "sell: $it" }
                append(" {").append(parts.joinToString(", ")).append("}\n")
                continue
            }
            append('\n')
            if (e.item.key != e.id || e.item.data != null) line("item", quote(e.item.key))
            e.item.data?.let { block("data", it) }
            e.buy?.let { line("buy", it.toString()) }
            e.sell?.let { line("sell", it.toString()) }
            if (e.amount != 1) line("amount", e.amount.toString())
            e.name?.let { line("name", quote(it)) }
            if (e.lore.isNotEmpty()) line("lore", flowList(e.lore))
            e.icon?.let { line("icon", quote(it.key)) }
            if (e.commands.isNotEmpty()) line("commands", flowList(e.commands))
            e.permission?.let { line("permission", quote(it)) }
            for (server in listOf(false, true)) {
                val mine = e.limits.filter { it.server == server }
                if (mine.isEmpty()) continue
                val parts = mine.map { "${it.kind.name.lowercase()}: ${it.amount}" } + "per: ${mine.first().period.word}"
                line(if (server) "server-limit" else "limit", parts.joinToString(", ", "{", "}"))
            }
            if (e.requires.isNotEmpty()) line("requires", flowList(e.requires.map { it.toString() }))
            if (e.slot >= 0) line("slot", e.slot.toString())
        }
    }

    private fun StringBuilder.line(key: String, value: String) {
        append("    ").append(key).append(": ").append(value).append('\n')
    }

    private fun StringBuilder.block(key: String, value: String) {
        append("    ").append(key).append(": |\n")
        for (l in value.trimEnd('\n').lines()) append("      ").append(l).append('\n')
    }

    public fun currencyText(currency: Currency): String = when (currency) {
        is Currency.Items -> "item:" + currency.item.key
        else -> currency.id
    }

    private fun flowList(items: List<String>): String = items.joinToString(", ", "[", "]") { quote(it, always = true) }

    private val PLAIN = Regex("^[a-z0-9_.]+$")

    /** [text] as a YAML scalar: as it is when that's safe, otherwise in double quotes. */
    public fun quote(text: String, always: Boolean = false): String {
        if (!always && PLAIN.matches(text) && text !in setOf("yes", "no", "true", "false", "null", "on", "off", "y", "n")) return text
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
