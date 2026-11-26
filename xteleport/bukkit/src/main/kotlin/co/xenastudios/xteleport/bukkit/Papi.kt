package co.xenastudios.xteleport.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xteleport.XTeleportPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/**
 * `%xteleport_homes%` and the rest for PlaceholderAPI, while it's installed: answered from memory
 * ([co.xenastudios.xteleport.Placeholders]). The one class that names PlaceholderAPI's, so it's
 * never loaded without it.
 */
public class Papi(private val xteleport: XTeleportPlugin) : PlaceholderExpansion() {
    override fun getIdentifier(): String = XTeleportPlugin.PLUGIN_ID

    override fun getAuthor(): String = "Xena Studios"

    override fun getVersion(): String = xteleport.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? = xteleport.placeholders.resolve(params, player?.ref)
}

/**
 * Where the entry point reaches [Papi], by `Any`: so verifying the entry point never loads
 * PlaceholderAPI's classes, which a server without it doesn't have (r8/xteleport.pro pins it).
 */
public object PapiHook {
    @JvmStatic
    public fun register(xteleport: XTeleportPlugin): Any = Papi(xteleport).also { it.register() }

    @JvmStatic
    public fun unregister(expansion: Any) {
        (expansion as Papi).unregister()
    }
}
