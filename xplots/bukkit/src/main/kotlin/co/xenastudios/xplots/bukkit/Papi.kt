package co.xenastudios.xplots.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xplots.XPlotsPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xplots_owned%` and the rest for PlaceholderAPI, while it's installed: answered from memory
 * ([co.xenastudios.xplots.Placeholders]). The one class that names PlaceholderAPI's, so it's
 * never loaded without it.
 */
public class Papi(private val xplots: XPlotsPlugin) : PlaceholderExpansion() {
    override fun getIdentifier(): String = XPlotsPlugin.PLUGIN_ID

    override fun getAuthor(): String = "Xena Studios"

    override fun getVersion(): String = xplots.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = xplots.placeholders.resolve(params, player?.ref)
}

/**
 * Where the entry point reaches [Papi], by `Any`: so verifying the entry point never loads
 * PlaceholderAPI's classes, which a server without it doesn't have (r8/xplots.pro pins it).
 */
public object PapiHook {
    @JvmStatic
    public fun register(xplots: XPlotsPlugin): Any = Papi(xplots).also { it.register() }

    @JvmStatic
    public fun unregister(expansion: Any) {
        (expansion as Papi).unregister()
    }
}
