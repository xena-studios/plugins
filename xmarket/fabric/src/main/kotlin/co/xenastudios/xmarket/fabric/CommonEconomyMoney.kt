package co.xenastudios.xmarket.fabric

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.player
import co.xenastudios.xmarket.Economy
import co.xenastudios.xmarket.api.Money
import eu.pb4.common.economy.api.CommonEconomy
import eu.pb4.common.economy.api.EconomyAccount

/**
 * Money through Patbox's Common Economy API, for a Fabric server whose economy is another mod's:
 * the player's first account, an amount crossing as the currency's own units
 * (`parseValue("12.5")`), so nothing is rounded through a double. Only loaded when the API's mod
 * is there. 1.20.1 and 1.21.11 speak `long`s, 26.x `BigInteger`s too.
 */
internal class CommonEconomyMoney private constructor(private val platform: FabricPlatform) : Economy {
    override val name: String get() = "Common Economy API"

    private fun account(player: PlayerRef): EconomyAccount? {
        val p = platform.server?.player(player) ?: return null
        return CommonEconomy.getAccounts(p).firstOrNull()
    }

    private fun units(account: EconomyAccount, amount: Money): Long =
        //? if economy_bigint {
        account.currency().parseValue(amount.toString()).toLong()
        //?} else
        //account.currency().parseValue(amount.toString())

    override fun has(player: PlayerRef, amount: Money): Boolean {
        val a = account(player) ?: return false
        return try {
            a.canDecreaseBalance(units(a, amount)).isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override fun withdraw(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> {
        val a = account(player) ?: return pendingOf(false)
        return pendingOf(try { a.decreaseBalance(units(a, amount)).isSuccessful } catch (e: Exception) { false })
    }

    override fun deposit(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> {
        val a = account(player) ?: return pendingOf(false)
        return pendingOf(try { a.increaseBalance(units(a, amount)).isSuccessful } catch (e: Exception) { false })
    }

    override fun format(amount: Money): String? {
        val server = platform.server ?: return null
        val currency = CommonEconomy.getCurrencies(server).firstOrNull() ?: return null
        return try {
            //? if economy_bigint {
            currency.formatValue(currency.parseValue(amount.toString()), true)
            //?} else
            //currency.formatValue(currency.parseValue(amount.toString()), true)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** The API's money, if some mod provides a currency; `null` otherwise. */
        fun find(platform: FabricPlatform): Economy? {
            val server = platform.server ?: return null
            return if (CommonEconomy.getCurrencies(server).isEmpty()) null else CommonEconomyMoney(platform)
        }
    }
}
