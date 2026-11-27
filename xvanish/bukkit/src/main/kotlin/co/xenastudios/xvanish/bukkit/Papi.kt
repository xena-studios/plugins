package co.xenastudios.xvanish.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xvanish.XVanishPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xvanish_vanished%` and the rest for PlaceholderAPI, while it's installed: answered from memory
 * ([co.xenastudios.xvanish.Placeholders]). The one class that names PlaceholderAPI's.
 */
public class Papi(private val xvanish: XVanishPlugin) : PlaceholderExpansion() {
    override fun getIdentifier(): String = XVanishPlugin.PLUGIN_ID

    override fun getAuthor(): String = "Xena Studios"

    override fun getVersion(): String = xvanish.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = xvanish.placeholders.resolve(params, player?.ref)
}

/** Where the entry point reaches [Papi], by `Any`: so verifying the entry point never loads PlaceholderAPI's classes. */
public object PapiHook {
    @JvmStatic
    public fun register(xvanish: XVanishPlugin): Any = Papi(xvanish).also { it.register() }

    @JvmStatic
    public fun unregister(expansion: Any) {
        (expansion as Papi).unregister()
    }
}
