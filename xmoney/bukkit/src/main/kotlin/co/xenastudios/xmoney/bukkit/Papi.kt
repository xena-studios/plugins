package co.xenastudios.xmoney.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xmoney.XMoneyPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xmoney_balance%` and the rest for PlaceholderAPI, while it's installed: answered from memory
 * ([co.xenastudios.xmoney.Placeholders]), so scoreboards can ask as often as they like. The one
 * class that names PlaceholderAPI's, so it's never loaded without it.
 */
public class Papi(private val xmoney: XMoneyPlugin) : PlaceholderExpansion() {
    override fun getIdentifier(): String = XMoneyPlugin.PLUGIN_ID

    override fun getAuthor(): String = "Xena Studios"

    override fun getVersion(): String = xmoney.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = xmoney.placeholders.resolve(params, player?.ref)
}

/**
 * Where the entry point reaches [Papi], by `Any`: so verifying the entry point never loads
 * PlaceholderAPI's classes, which a server without it doesn't have. This class loads only once
 * PlaceholderAPI is enabled (r8/vault.pro pins it, so R8 doesn't inline it back).
 */
public object PapiHook {
    @JvmStatic
    public fun register(xmoney: XMoneyPlugin): Any = Papi(xmoney).also { it.register() }

    @JvmStatic
    public fun unregister(expansion: Any) {
        (expansion as Papi).unregister()
    }
}
