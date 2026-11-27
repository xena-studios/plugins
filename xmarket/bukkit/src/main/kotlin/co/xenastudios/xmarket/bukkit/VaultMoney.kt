package co.xenastudios.xmarket.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.pendingOf
import co.xenastudios.xmarket.Economy
import co.xenastudios.xmarket.api.Money
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import java.lang.reflect.Method

/**
 * Money through whatever economy is registered with Vault, for servers without xMoney:
 * EssentialsX's, CMI's and the rest. Vault speaks doubles, so an amount crosses as the nearest
 * double to its exact value (a cent is exact in a double up to trillions); xMarket's own sums
 * stay exact. Called by name through the class Vault registered it under, so xMarket needs no
 * Vault on its class path; on the player's thread, as Vault expects.
 */
internal class VaultMoney private constructor(
    private val provider: Any,
    private val hasMethod: Method,
    private val withdrawMethod: Method,
    private val depositMethod: Method,
    private val formatMethod: Method,
    private val success: Method,
    private val server: Server,
    providerName: String,
) : Economy {
    override val name: String = "Vault ($providerName)"

    private fun player(ref: PlayerRef): OfflinePlayer = server.getOfflinePlayer(ref.javaUuid)

    private fun value(amount: Money): Double = amount.units / Money.ONE.toDouble()

    override fun has(player: PlayerRef, amount: Money): Boolean = try {
        hasMethod.invoke(provider, player(player), value(amount)) == true
    } catch (e: Exception) {
        false
    }

    override fun withdraw(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> = call(withdrawMethod, player, amount)

    override fun deposit(player: PlayerRef, amount: Money, reason: String): Pending<Boolean> = call(depositMethod, player, amount)

    private fun call(method: Method, player: PlayerRef, amount: Money): Pending<Boolean> = try {
        pendingOf(success.invoke(method.invoke(provider, player(player), value(amount))) == true)
    } catch (e: Exception) {
        pendingOf(false)
    }

    override fun format(amount: Money): String? = try {
        formatMethod.invoke(provider, value(amount)) as String?
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The economy registered with Vault, or `null` without one. */
        fun find(server: Server): VaultMoney? = try {
            val services = server.servicesManager
            val type = services.knownServices.firstOrNull { it.name == "net.milkbowl.vault.economy.Economy" }
            val registration = type?.let { services.getRegistration(it) }
            val provider = registration?.provider
            if (provider == null) {
                null
            } else {
                val offline = OfflinePlayer::class.java
                val double = Double::class.javaPrimitiveType
                val withdraw = type.getMethod("withdrawPlayer", offline, double)
                VaultMoney(
                    provider, type.getMethod("has", offline, double), withdraw, type.getMethod("depositPlayer", offline, double),
                    type.getMethod("format", double), withdraw.returnType.getMethod("transactionSuccess"), server,
                    (type.getMethod("getName").invoke(provider) as? String) ?: registration.plugin.name,
                )
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}
