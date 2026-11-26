package co.xenastudios.xmoney.bukkit.vault

import co.xenastudios.xmoney.CurrencyFormat
import co.xenastudios.xmoney.XMoneyPlugin
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Outcome
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID
import net.milkbowl.vault2.economy.AccountPermission
import net.milkbowl.vault2.economy.Economy
import net.milkbowl.vault2.economy.EconomyResponse
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType
import net.milkbowl.vault2.economy.MultiEconomyResponse

/**
 * VaultUnlocked 2's `Economy`: by uuid, in `BigDecimal`s, with the calling plugin's name, which
 * the ledger records. One currency (`default`, or its singular or plural name), no shared
 * accounts. The same rules as [LegacyEconomy]: four places kept, deposits rounded down and
 * withdrawals up past them, and a positive withdrawal never takes nothing.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class UnlockedEconomy(private val holder: VaultHolder) : Economy {
    private val bridge: VaultBridge get() = holder.get()


    private fun ours(currency: String?): Boolean {
        if (currency.isNullOrBlank()) return true
        val settings = bridge.xmoney.compiled.format.settings
        return currency.equals(CurrencyFormat.ID, ignoreCase = true) || currency.equals(settings.singular, ignoreCase = true) ||
            currency.equals(settings.plural, ignoreCase = true)
    }

    private fun decimal(units: Long) = bridge.decimal(units)

    override fun isEnabled(): Boolean = bridge.plugin.isEnabled

    override fun getName(): String = XMoneyPlugin.NAME

    override fun hasSharedAccountSupport(): Boolean = false

    override fun hasMultiCurrencySupport(): Boolean = false

    override fun fractionalDigits(pluginName: String): Int = bridge.decimals

    override fun format(amount: BigDecimal): String {
        var units = bridge.shown(amount.abs())
        if (units < 0) units = Amount.MAX_UNITS
        return bridge.format(if (amount.signum() < 0) -units else units)
    }

    override fun format(pluginName: String, amount: BigDecimal): String = format(amount)

    override fun format(amount: BigDecimal, currency: String): String = format(amount)

    override fun format(pluginName: String, amount: BigDecimal, currency: String): String = format(amount)

    override fun hasCurrency(currency: String): Boolean = ours(currency)

    override fun getDefaultCurrency(pluginName: String): String = CurrencyFormat.ID

    override fun defaultCurrencyNamePlural(pluginName: String): String = bridge.xmoney.compiled.format.settings.plural

    override fun defaultCurrencyNameSingular(pluginName: String): String = bridge.xmoney.compiled.format.settings.singular

    override fun currencies(): Collection<String> = listOf(CurrencyFormat.ID)

    // ---- accounts ----

    override fun createAccount(id: UUID, name: String): Boolean = createAccount(id, name, true)

    override fun createAccount(id: UUID, name: String, player: Boolean): Boolean {
        if (bridge.existing(id) != null) return false
        val ledger = bridge.ledger ?: return false
        ledger.account(co.xenastudios.core.api.PlayerRef.of(id.toKotlin()), name, player)
        return true
    }

    override fun createAccount(id: UUID, name: String, world: String): Boolean = createAccount(id, name, true)

    override fun createAccount(id: UUID, name: String, world: String, player: Boolean): Boolean = createAccount(id, name, player)

    override fun getUUIDNameMap(): Map<UUID, String> {
        val names = HashMap<UUID, String>()
        for (account in bridge.ledger?.accounts?.all().orEmpty()) names[account.id.toJava()] = account.name
        return names
    }

    override fun getAccountName(id: UUID): Optional<String> = Optional.ofNullable(bridge.existing(id)?.name)

    override fun hasAccount(id: UUID): Boolean = bridge.existing(id) != null

    override fun hasAccount(id: UUID, world: String): Boolean = hasAccount(id)

    override fun renameAccount(id: UUID, name: String): Boolean {
        val account = bridge.existing(id) ?: return false
        // Not to a name another account is known by: name-based calls would find the wrong one.
        return bridge.ledger?.rename(account, name) == true
    }

    override fun renameAccount(pluginName: String, id: UUID, name: String): Boolean = renameAccount(id, name)

    override fun deleteAccount(pluginName: String, id: UUID): Boolean = false

    override fun accountSupportsCurrency(pluginName: String, id: UUID, currency: String): Boolean = ours(currency)

    override fun accountSupportsCurrency(pluginName: String, id: UUID, currency: String, world: String): Boolean = ours(currency)

    // ---- balances ----

    override fun getBalance(pluginName: String, id: UUID): BigDecimal = decimal(bridge.balance(bridge.existing(id)))

    override fun getBalance(pluginName: String, id: UUID, world: String): BigDecimal = getBalance(pluginName, id)

    override fun getBalance(pluginName: String, id: UUID, world: String, currency: String): BigDecimal =
        if (ours(currency)) getBalance(pluginName, id) else BigDecimal.ZERO

    override fun balance(pluginName: String, id: UUID): BigDecimal = getBalance(pluginName, id)

    override fun balance(pluginName: String, id: UUID, world: String): BigDecimal = getBalance(pluginName, id)

    override fun balance(pluginName: String, id: UUID, world: String, currency: String): BigDecimal = getBalance(pluginName, id, world, currency)

    override fun has(pluginName: String, id: UUID, amount: BigDecimal): Boolean = bridge.has(bridge.existing(id), amount)

    override fun has(pluginName: String, id: UUID, world: String, amount: BigDecimal): Boolean = has(pluginName, id, amount)

    override fun has(pluginName: String, id: UUID, world: String, currency: String, amount: BigDecimal): Boolean =
        if (ours(currency)) has(pluginName, id, amount) else amount.signum() <= 0

    // ---- changes ----

    override fun withdraw(pluginName: String, id: UUID, amount: BigDecimal): EconomyResponse {
        val account = bridge.existing(id)
        val units = bridge.outgoing(amount)
        if (units < 0) return failure(account, "Not a valid amount")
        if (units == 0L) return EconomyResponse(BigDecimal.ZERO, decimal(bridge.balance(account)), ResponseType.SUCCESS, "")
        if (account == null) return failure(null, "Insufficient funds")
        return response(bridge.withdraw(account, units, bridge.cause(pluginName)))
    }

    override fun withdraw(pluginName: String, id: UUID, world: String, amount: BigDecimal): EconomyResponse = withdraw(pluginName, id, amount)

    override fun withdraw(pluginName: String, id: UUID, world: String, currency: String, amount: BigDecimal): EconomyResponse =
        if (ours(currency)) withdraw(pluginName, id, amount) else unknownCurrency(id)

    override fun deposit(pluginName: String, id: UUID, amount: BigDecimal): EconomyResponse {
        val units = bridge.incoming(amount)
        if (units < 0) return failure(bridge.existing(id), "Not a valid amount")
        val account = bridge.payee(id) ?: return failure(null, "The economy isn't ready")
        if (units == 0L) return EconomyResponse(BigDecimal.ZERO, decimal(account.balance), ResponseType.SUCCESS, "")
        return response(bridge.deposit(account, units, bridge.cause(pluginName)))
    }

    override fun deposit(pluginName: String, id: UUID, world: String, amount: BigDecimal): EconomyResponse = deposit(pluginName, id, amount)

    override fun deposit(pluginName: String, id: UUID, world: String, currency: String, amount: BigDecimal): EconomyResponse =
        if (ours(currency)) deposit(pluginName, id, amount) else unknownCurrency(id)

    override fun set(pluginName: String, id: UUID, amount: BigDecimal): EconomyResponse {
        val units = bridge.incoming(amount)
        if (units < 0) return failure(bridge.existing(id), "Not a valid amount")
        val account = bridge.payee(id) ?: return failure(null, "The economy isn't ready")
        return response(bridge.set(account, units, bridge.cause(pluginName)))
    }

    override fun set(pluginName: String, id: UUID, world: String, amount: BigDecimal): EconomyResponse = set(pluginName, id, amount)

    override fun set(pluginName: String, id: UUID, world: String, currency: String, amount: BigDecimal): EconomyResponse =
        if (ours(currency)) set(pluginName, id, amount) else unknownCurrency(id)

    override fun transfer(pluginName: String, from: UUID, to: UUID, amount: BigDecimal): MultiEconomyResponse {
        val units = bridge.outgoing(amount)
        val payer = bridge.existing(from)
        if (units <= 0) return MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "Not a valid amount")
        if (payer == null) return MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "Insufficient funds")
        val payee = bridge.payee(to) ?: return MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "The economy isn't ready")
        val outcome = bridge.transfer(payer, payee, units, bridge.cause(pluginName))
        val response = MultiEconomyResponse(decimal(outcome.amount), if (outcome.success) ResponseType.SUCCESS else ResponseType.FAILURE, VaultBridge.reason(outcome))
        response.addBalance(from, decimal(payer.balance))
        response.addBalance(to, decimal(payee.balance))
        return response
    }

    override fun transfer(pluginName: String, from: UUID, to: UUID, world: String, amount: BigDecimal): MultiEconomyResponse =
        transfer(pluginName, from, to, amount)

    override fun transfer(pluginName: String, from: UUID, to: UUID, world: String, currency: String, amount: BigDecimal): MultiEconomyResponse =
        if (ours(currency)) transfer(pluginName, from, to, amount) else MultiEconomyResponse(BigDecimal.ZERO, ResponseType.FAILURE, "Unknown currency")

    override fun canWithdraw(pluginName: String, id: UUID, amount: BigDecimal): EconomyResponse {
        val account = bridge.existing(id)
        val units = bridge.outgoing(amount)
        if (units < 0) return failure(account, "Not a valid amount")
        val enough = bridge.balance(account) >= units
        return EconomyResponse(if (enough) decimal(units) else BigDecimal.ZERO, decimal(bridge.balance(account)),
            if (enough) ResponseType.SUCCESS else ResponseType.FAILURE, if (enough) "" else "Insufficient funds")
    }

    override fun canWithdraw(pluginName: String, id: UUID, world: String, amount: BigDecimal): EconomyResponse = canWithdraw(pluginName, id, amount)

    override fun canWithdraw(pluginName: String, id: UUID, world: String, currency: String, amount: BigDecimal): EconomyResponse =
        if (ours(currency)) canWithdraw(pluginName, id, amount) else unknownCurrency(id)

    override fun canDeposit(pluginName: String, id: UUID, amount: BigDecimal): EconomyResponse {
        val account = bridge.existing(id)
        val units = bridge.incoming(amount)
        if (units < 0) return failure(account, "Not a valid amount")
        val fits = bridge.balance(account) + units <= bridge.max
        return EconomyResponse(if (fits) decimal(units) else BigDecimal.ZERO, decimal(bridge.balance(account)),
            if (fits) ResponseType.SUCCESS else ResponseType.FAILURE, if (fits) "" else "That would take the balance over the most it can hold")
    }

    override fun canDeposit(pluginName: String, id: UUID, world: String, amount: BigDecimal): EconomyResponse = canDeposit(pluginName, id, amount)

    override fun canDeposit(pluginName: String, id: UUID, world: String, currency: String, amount: BigDecimal): EconomyResponse =
        if (ours(currency)) canDeposit(pluginName, id, amount) else unknownCurrency(id)

    private fun failure(account: Account?, why: String) = EconomyResponse(BigDecimal.ZERO, decimal(bridge.balance(account)), ResponseType.FAILURE, why)

    private fun unknownCurrency(id: UUID?) = failure(bridge.existing(id), "Unknown currency")

    private fun response(outcome: Outcome) = EconomyResponse(
        decimal(outcome.amount), decimal(outcome.balance), if (outcome.success) ResponseType.SUCCESS else ResponseType.FAILURE, VaultBridge.reason(outcome),
    )

    // ---- shared accounts: none ----

    override fun createSharedAccount(pluginName: String, id: UUID, name: String, owner: UUID): Boolean = false

    override fun isAccountOwner(pluginName: String, id: UUID, player: UUID): Boolean = false

    override fun setOwner(pluginName: String, id: UUID, player: UUID): Boolean = false

    override fun isAccountMember(pluginName: String, id: UUID, player: UUID): Boolean = false

    override fun addAccountMember(pluginName: String, id: UUID, player: UUID): Boolean = false

    override fun addAccountMember(pluginName: String, id: UUID, player: UUID, vararg permissions: AccountPermission): Boolean = false

    override fun removeAccountMember(pluginName: String, id: UUID, player: UUID): Boolean = false

    override fun hasAccountPermission(pluginName: String, id: UUID, player: UUID, permission: AccountPermission): Boolean = false

    override fun updateAccountPermission(pluginName: String, id: UUID, player: UUID, permission: AccountPermission, value: Boolean): Boolean = false
}
