package co.xenastudios.xmoney.bukkit.vault

import co.xenastudios.xmoney.XMoneyPlugin
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Outcome
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer

/**
 * Vault 1's `Economy`, the API most plugins use. Every call answers from memory and is safe from
 * any thread (on a network, a withdrawal waits briefly for the shared database). Amounts keep four
 * decimal places: deposits are rounded down past them, withdrawals and `has` up. Amounts that
 * aren't numbers, are negative, or are too large to hold are refused; nothing is ever made from
 * them. Worlds are ignored: one balance everywhere. No banks, and it says so.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class LegacyEconomy(private val holder: VaultHolder) : Economy {
    private val bridge: VaultBridge get() = holder.get()


    override fun isEnabled(): Boolean = bridge.plugin.isEnabled

    override fun getName(): String = XMoneyPlugin.NAME

    override fun hasBankSupport(): Boolean = false

    override fun fractionalDigits(): Int = bridge.decimals

    override fun format(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) return bridge.format(0)
        var units = bridge.shown(kotlin.math.abs(amount))
        if (units < 0) units = Amount.MAX_UNITS
        return bridge.format(if (amount < 0) -units else units)
    }

    override fun currencyNamePlural(): String = bridge.xmoney.compiled.format.settings.plural

    override fun currencyNameSingular(): String = bridge.xmoney.compiled.format.settings.singular

    // ---- accounts ----

    override fun hasAccount(playerName: String?): Boolean = bridge.existing(playerName) != null

    override fun hasAccount(player: OfflinePlayer?): Boolean = bridge.existing(player) != null

    override fun hasAccount(playerName: String?, worldName: String?): Boolean = hasAccount(playerName)

    override fun hasAccount(player: OfflinePlayer?, worldName: String?): Boolean = hasAccount(player)

    override fun createPlayerAccount(playerName: String?): Boolean {
        if (playerName.isNullOrBlank() || bridge.existing(playerName) != null) return false
        return bridge.payee(playerName) != null
    }

    override fun createPlayerAccount(player: OfflinePlayer?): Boolean {
        if (player == null || bridge.existing(player) != null) return false
        return bridge.payee(player) != null
    }

    override fun createPlayerAccount(playerName: String?, worldName: String?): Boolean = createPlayerAccount(playerName)

    override fun createPlayerAccount(player: OfflinePlayer?, worldName: String?): Boolean = createPlayerAccount(player)

    // ---- balances ----

    override fun getBalance(playerName: String?): Double = Amount.ofUnits(bridge.balance(bridge.existing(playerName))).toDouble()

    override fun getBalance(player: OfflinePlayer?): Double = Amount.ofUnits(bridge.balance(bridge.existing(player))).toDouble()

    override fun getBalance(playerName: String?, world: String?): Double = getBalance(playerName)

    override fun getBalance(player: OfflinePlayer?, world: String?): Double = getBalance(player)

    override fun has(playerName: String?, amount: Double): Boolean = bridge.has(bridge.existing(playerName), amount)

    override fun has(player: OfflinePlayer?, amount: Double): Boolean = bridge.has(bridge.existing(player), amount)

    override fun has(playerName: String?, worldName: String?, amount: Double): Boolean = has(playerName, amount)

    override fun has(player: OfflinePlayer?, worldName: String?, amount: Double): Boolean = has(player, amount)

    // ---- changes ----

    override fun withdrawPlayer(playerName: String?, amount: Double): EconomyResponse = withdraw(bridge.existing(playerName), amount)

    override fun withdrawPlayer(player: OfflinePlayer?, amount: Double): EconomyResponse = withdraw(bridge.existing(player), amount)

    override fun withdrawPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(playerName, amount)

    override fun withdrawPlayer(player: OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(player, amount)

    override fun depositPlayer(playerName: String?, amount: Double): EconomyResponse {
        val units = bridge.incoming(amount)
        if (units < 0) return invalid(bridge.existing(playerName))
        return deposit(bridge.payee(playerName), units)
    }

    override fun depositPlayer(player: OfflinePlayer?, amount: Double): EconomyResponse {
        val units = bridge.incoming(amount)
        if (units < 0 || player == null) return invalid(bridge.existing(player))
        return deposit(bridge.payee(player), units)
    }

    override fun depositPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = depositPlayer(playerName, amount)

    override fun depositPlayer(player: OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = depositPlayer(player, amount)

    private fun withdraw(account: Account?, amount: Double): EconomyResponse {
        val units = bridge.outgoing(amount)
        if (units < 0) return invalid(account)
        // Only an amount of exactly 0: a positive one is rounded up to at least one unit.
        if (units == 0L) return EconomyResponse(0.0, double(bridge.balance(account)), EconomyResponse.ResponseType.SUCCESS, "")
        if (account == null) return EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "Insufficient funds")
        return response(bridge.withdraw(account, units, bridge.cause(null)))
    }

    private fun deposit(account: Account?, units: Long): EconomyResponse {
        if (account == null) return invalid(null)
        // Zero, or less than a ten-thousandth, rounded down: nothing is paid in, and nothing made.
        if (units == 0L) return EconomyResponse(0.0, double(account.balance), EconomyResponse.ResponseType.SUCCESS, "")
        return response(bridge.deposit(account, units, bridge.cause(null)))
    }

    private fun invalid(account: Account?) =
        EconomyResponse(0.0, double(bridge.balance(account)), EconomyResponse.ResponseType.FAILURE, "Not a valid amount")

    private fun response(outcome: Outcome) = EconomyResponse(
        double(outcome.amount), double(outcome.balance),
        if (outcome.success) EconomyResponse.ResponseType.SUCCESS else EconomyResponse.ResponseType.FAILURE, VaultBridge.reason(outcome),
    )

    private fun double(units: Long) = Amount.ofUnits(units).toDouble()

    // ---- banks: none ----

    private fun noBanks() = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "${XMoneyPlugin.NAME} doesn't have banks.")

    override fun createBank(name: String?, player: String?): EconomyResponse = noBanks()

    override fun createBank(name: String?, player: OfflinePlayer?): EconomyResponse = noBanks()

    override fun deleteBank(name: String?): EconomyResponse = noBanks()

    override fun bankBalance(name: String?): EconomyResponse = noBanks()

    override fun bankHas(name: String?, amount: Double): EconomyResponse = noBanks()

    override fun bankWithdraw(name: String?, amount: Double): EconomyResponse = noBanks()

    override fun bankDeposit(name: String?, amount: Double): EconomyResponse = noBanks()

    override fun isBankOwner(name: String?, playerName: String?): EconomyResponse = noBanks()

    override fun isBankOwner(name: String?, player: OfflinePlayer?): EconomyResponse = noBanks()

    override fun isBankMember(name: String?, playerName: String?): EconomyResponse = noBanks()

    override fun isBankMember(name: String?, player: OfflinePlayer?): EconomyResponse = noBanks()

    override fun getBanks(): List<String> = emptyList()
}
