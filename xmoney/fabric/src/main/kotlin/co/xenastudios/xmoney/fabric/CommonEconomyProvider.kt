package co.xenastudios.xmoney.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xmoney.XMoneyPlugin
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Cause
import co.xenastudios.xmoney.ledger.Outcome
import com.mojang.authlib.GameProfile
import eu.pb4.common.economy.api.EconomyAccount
import eu.pb4.common.economy.api.EconomyCurrency
import eu.pb4.common.economy.api.EconomyProvider
import eu.pb4.common.economy.api.EconomyTransaction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.UUID
//? if economy_bigint
import java.math.BigInteger
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier

/**
 * xMoney as Patbox's Common Economy API's provider (`xmoney`), on every Fabric line: one currency
 * (`xmoney:default`) and one account a player (`xmoney:default`), whose balance is xMoney's, in
 * its units (ten-thousandths: `parseValue("12.5")` is 125000). Changes go through xMoney's ledger
 * as from the Common Economy API, so `/eco history` shows them; on a network, a withdrawal waits
 * for the shared database (five seconds at most, refused if it doesn't answer). 1.20.1 and 1.21.11
 * speak `long`s, 26.x `BigInteger`s.
 */
internal class CommonEconomyProvider(private val xmoney: XMoneyPlugin) : EconomyProvider {
    private val currency = Currency()

    override fun name(): Component = Component.literal(XMoneyPlugin.NAME)

    override fun getAccount(server: MinecraftServer, profile: GameProfile, accountId: String): EconomyAccount? =
        if (accountId == ACCOUNT) account(profile) else null

    override fun getAccounts(server: MinecraftServer, profile: GameProfile): Collection<EconomyAccount> = listOfNotNull(account(profile))

    override fun getCurrency(server: MinecraftServer, currencyId: String): EconomyCurrency? = if (currencyId == ACCOUNT) currency else null

    override fun getCurrencies(server: MinecraftServer): Collection<EconomyCurrency> = listOf(currency)

    override fun defaultAccount(server: MinecraftServer, profile: GameProfile, currency: EconomyCurrency): String = ACCOUNT

    private fun account(profile: GameProfile): EconomyAccount? {
        val ledger = xmoney.ledger ?: return null
        val id = PlayerRef.parseOrNull(profile.id.toString()) ?: return null
        val account = ledger.accounts[id] ?: ledger.account(id, profile.name, true)
        return PlayerAccount(account)
    }

    private fun cause() = Cause(TransactionKind.PLUGIN, "Common Economy API", "")

    // A ledger change's answer now, as the Common Economy API needs it: complete at once on one
    // server, waited for (five seconds at most) on a network.
    private fun now(result: co.xenastudios.core.api.Pending<Outcome>, account: Account): Outcome = try {
        result.get(co.xenastudios.xmoney.ledger.Ledger.WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        if (xmoney.ledger?.abandon(result, Outcome(Status.UNAVAILABLE, 0, account.balance)) == true) Outcome(Status.UNAVAILABLE, 0, account.balance) else result.join()
    }

    private fun message(outcome: Outcome): Component = Component.literal(
        when (outcome.status) {
            Status.SUCCESS -> ""
            Status.INSUFFICIENT_FUNDS -> "Not enough money"
            Status.BALANCE_LIMIT -> "That would take the balance past what it can hold"
            Status.UNAVAILABLE -> "The economy's database didn't answer in time"
            Status.CANCELLED -> "Refused by another plugin"
            else -> "Not a valid amount"
        },
    )

    private inner class PlayerAccount(private val account: Account) : EconomyAccount {
        override fun name(): Component = Component.literal(account.name)

        override fun owner(): UUID = UUID.fromString(account.id.toString())

        override fun id(): Identifier = ID

        override fun provider(): EconomyProvider = this@CommonEconomyProvider

        override fun currency(): EconomyCurrency = currency

        private fun change(before: Long, outcome: Outcome, amount: Long): EconomyTransaction =
            EconomyTransaction.Simple(outcome.success, message(outcome), big(account.balance), big(before), big(amount), this)

        private fun check(ok: Boolean, why: String, amount: Long): EconomyTransaction =
            EconomyTransaction.Simple(ok, Component.literal(why), big(account.balance), big(account.balance), big(amount), this)

        //? if economy_bigint {
        override fun balance(): BigInteger = BigInteger.valueOf(account.balance)

        override fun canIncreaseBalance(value: BigInteger): EconomyTransaction = canIncrease(value.toLongOrMax())

        override fun canDecreaseBalance(value: BigInteger): EconomyTransaction = canDecrease(value.toLongOrMax())

        override fun increaseBalance(value: BigInteger): EconomyTransaction = increase(value.toLongOrMax())

        override fun decreaseBalance(value: BigInteger): EconomyTransaction = decrease(value.toLongOrMax())

        override fun setBalance(value: BigInteger) {
            set(value.toLongOrMax())
        }
        //?} else {
        /*override fun balance(): Long = account.balance

        override fun canIncreaseBalance(value: Long): EconomyTransaction = canIncrease(value)

        override fun canDecreaseBalance(value: Long): EconomyTransaction = canDecrease(value)

        override fun increaseBalance(value: Long): EconomyTransaction = increase(value)

        override fun decreaseBalance(value: Long): EconomyTransaction = decrease(value)

        override fun setBalance(value: Long) {
            set(value)
        }
        *///?}

        private fun canIncrease(value: Long): EconomyTransaction =
            if (value > 0 && account.balance + value <= xmoney.compiled.max) check(true, "", value) else check(false, "That would take the balance past what it can hold", value)

        private fun canDecrease(value: Long): EconomyTransaction =
            if (value > 0 && account.balance >= value) check(true, "", value) else check(false, "Not enough money", value)

        private fun increase(value: Long): EconomyTransaction {
            val before = account.balance
            val ledger = xmoney.ledger ?: return check(false, "The economy isn't ready", value)
            return change(before, now(ledger.deposit(account, value, cause()), account), value)
        }

        private fun decrease(value: Long): EconomyTransaction {
            val before = account.balance
            val ledger = xmoney.ledger ?: return check(false, "The economy isn't ready", value)
            return change(before, now(ledger.withdraw(account, value, cause()), account), value)
        }

        private fun set(value: Long) {
            val ledger = xmoney.ledger ?: return
            now(ledger.set(account, value.coerceAtLeast(0), cause()), account)
        }
    }

    private inner class Currency : EconomyCurrency {
        override fun name(): Component = Component.literal(xmoney.compiled.format.settings.plural)

        override fun id(): Identifier = ID

        override fun provider(): EconomyProvider = this@CommonEconomyProvider

        //? if economy_bigint {
        override fun formatValue(value: BigInteger, precise: Boolean): String = xmoney.format(value.toLongOrMax())

        override fun parseValue(value: String): BigInteger = BigInteger.valueOf(parse(value))
        //?} else {
        /*override fun formatValue(value: Long, precise: Boolean): String = xmoney.format(value)

        override fun parseValue(value: String): Long = parse(value)
        *///?}

        private fun parse(value: String): Long {
            val parsed = xmoney.compiled.format.parse(value)
            if (parsed.problem != null) throw NumberFormatException("\"$value\" isn't an amount")
            return parsed.units
        }
    }

    private companion object {
        const val ACCOUNT = "default"

        //? if identifier {
        val ID: Identifier = Identifier.fromNamespaceAndPath(XMoneyPlugin.PLUGIN_ID, ACCOUNT)
        //?} else
        /*val ID: Identifier = Identifier(XMoneyPlugin.PLUGIN_ID, ACCOUNT)*/
    }
}

//? if economy_bigint {
private fun BigInteger.toLongOrMax(): Long = if (bitLength() < 63) toLong() else if (signum() < 0) Long.MIN_VALUE else Long.MAX_VALUE
//?}

//? if !economy_bigint
/*private fun big(value: Long): Long = value*/
//? if economy_bigint
private fun big(value: Long): BigInteger = BigInteger.valueOf(value)
