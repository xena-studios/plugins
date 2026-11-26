package co.xenastudios.xteleport.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.javaUuid
import co.xenastudios.core.api.pendingOf
import co.xenastudios.xteleport.Economy
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/**
 * Teleport costs through whatever economy is registered with Vault, as 1.x took them, for servers
 * without xMoney: EssentialsX's, CMI's and the rest. Vault's API is called by name through the
 * class it was registered under, so xTeleport needs no Vault on its class path. Vault's calls are
 * made on the player's thread, as Vault expects.
 */
internal class VaultEconomy private constructor(
    private val provider: Any,
    private val has: Method,
    private val withdraw: Method,
    private val deposit: Method,
    private val format: Method,
    private val success: Method,
    private val server: Server,
    providerName: String,
) : Economy {
    override val name: String = "Vault ($providerName)"

    private fun player(ref: PlayerRef): OfflinePlayer = server.getOfflinePlayer(ref.javaUuid)

    override fun has(player: PlayerRef, amount: Double): Boolean = try {
        has.invoke(provider, player(player), amount) as Boolean
    } catch (e: Exception) {
        false
    }

    override fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = change(withdraw, player, amount)

    override fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = change(deposit, player, amount)

    private fun change(method: Method, player: PlayerRef, amount: Double): Pending<Boolean> = try {
        pendingOf(success.invoke(method.invoke(provider, player(player), amount)) == true)
    } catch (e: Exception) {
        pendingOf(false)
    }

    override fun format(amount: Double): String? = try {
        format.invoke(provider, amount) as String?
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val ECONOMY = "net.milkbowl.vault.economy.Economy"

        /** The economy registered with Vault, and the plugin it's from; `null` without one. */
        fun find(server: Server): Pair<Plugin, Economy>? = try {
            val services = server.servicesManager
            val type = services.knownServices.firstOrNull { it.name == ECONOMY }
            val registration = type?.let { services.getRegistration(it) }
            val provider = registration?.provider
            if (provider == null) {
                null
            } else {
                val offline = OfflinePlayer::class.java
                val double = Double::class.javaPrimitiveType
                val withdraw = type.getMethod("withdrawPlayer", offline, double)
                val response = withdraw.returnType
                registration.plugin to VaultEconomy(
                    provider, type.getMethod("has", offline, double), withdraw, type.getMethod("depositPlayer", offline, double),
                    type.getMethod("format", double), response.getMethod("transactionSuccess"), server,
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
