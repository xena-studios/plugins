package co.xenastudios.xmoney

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.placeholder.PlaceholderSet
import co.xenastudios.core.common.placeholder.placeholders

/**
 * xMoney's placeholders, answered from memory, so scoreboards can ask as often as they like:
 * `balance`, `balance_raw` and `balance_short` for the player, and `top_<rank>_name` and
 * `top_<rank>_balance` (with `_raw` and `_short`) for ranks 1 to 100. PlaceholderAPI reads them
 * as `%xmoney_balance%` on Bukkit; zChat's formats as `<balance>`.
 */
public class Placeholders(private val plugin: XMoneyPlugin) {
    private var made: PlaceholderSet? = null

    /**
     * The ones with fixed names, as the docs list them: made the first time it's asked for (the
     * reference does; PlaceholderAPI and zChat call [resolve]), so its builder's lambdas never load
     * on a server (decisions/2026-11-25-a-lighter-core.md).
     */
    public fun set(): PlaceholderSet = made ?: build().also { made = it }

    private fun build(): PlaceholderSet = placeholders(XMoneyPlugin.PLUGIN_ID) {
        player("balance", "The player's balance, formatted.", "$1,234.50") { resolve("balance", it) }
        player("balance_raw", "Just the number, with the currency's decimals.", "1234.50") { resolve("balance_raw", it) }
        player("balance_short", "Short, for scoreboards.", "1.2k") { resolve("balance_short", it) }
        global("top_1_name", "Who's at that place in /baltop: any rank from 1 to 100 in place of 1.", "Steve") { resolve("top_1_name", null) }
        global("top_1_balance", "Their balance, formatted. Also _balance_raw and _balance_short.", "$1,234.50") { resolve("top_1_balance", null) }
    }

    /** The value of the placeholder called [name] (without `xmoney_`) for [player], or `null` if there's no such placeholder. */
    public fun resolve(name: String, player: PlayerRef?): String? {
        val format = plugin.compiled.format
        if (name.startsWith("balance")) {
            val units = plugin.balanceOf(player ?: return null)
            return shown(format, name.removePrefix("balance"), units)
        }
        if (!name.startsWith("top_")) return null
        val rest = name.removePrefix("top_")
        val rank = rest.substringBefore('_').toIntOrNull()?.takeIf { it in 1..100 } ?: return null
        val field = rest.substringAfter('_', "")
        val entry = plugin.ledger?.accounts?.top(rank, 1)?.firstOrNull()
        return when {
            field == "name" -> entry?.account?.name ?: ""
            field.startsWith("balance") -> shown(format, field.removePrefix("balance"), entry?.balance ?: 0)
            else -> null
        }
    }

    private fun shown(format: CurrencyFormat, suffix: String, units: Long): String? = when (suffix) {
        "" -> format.format(units)
        "_raw" -> format.plain(units)
        "_short" -> format.shortForm(units)
        else -> null
    }
}
