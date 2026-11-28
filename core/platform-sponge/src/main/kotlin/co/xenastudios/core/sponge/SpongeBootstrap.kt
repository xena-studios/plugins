package co.xenastudios.core.sponge

import co.xenastudios.core.jvm.family.JvmFamilyBoard
import org.spongepowered.plugin.PluginContainer
import java.util.function.Supplier
import kotlin.concurrent.Volatile

/**
 * What every Xena plugin's Sponge main class extends: Sponge makes the class named in
 * `META-INF/sponge_plugins.json` with Guice, which hands it the plugin's [PluginContainer]:
 *
 *     @Plugin("xsample")
 *     class XSampleSponge @Inject constructor(container: PluginContainer) : SpongeBootstrap(container) {
 *         init { val platform = SpongePlatform(this, "xSample", "1.0.0") ... }
 *     }
 *
 * It's how the Xena plugins on a server find each other's family board (DECISIONS.md, "The
 * family"): Sponge has no registry for objects plugins share, so the board goes on each plugin's
 * main object, which hands it out as a JDK [Supplier] ([JvmFamilyBoard.fromShims], by this
 * class's name in whatever package the plugin relocated its core to), as the proxies' shims do.
 * Its name is kept by the jar's R8 rules.
 */
public abstract class SpongeBootstrap(
    /** The plugin, as Sponge knows it. */
    public val container: PluginContainer,
) : Supplier<Any?> {

    @Volatile
    private var board: Any? = null

    init {
        first = first ?: container
    }

    /** The family board this plugin shares, once its platform found it. */
    final override fun get(): Any? = board

    internal fun share(root: Any) {
        board = root
    }

    internal companion object {
        /** The class name [JvmFamilyBoard.fromShims] looks for, in any package. */
        const val SHIM: String = "core.sponge.SpongeBootstrap"

        @Volatile
        private var first: PluginContainer? = null

        /** A container of this plugin's, for work that has to name one (a task the core sends to the server thread). */
        fun anyContainer(): PluginContainer = first ?: error("no Xena plugin has started on Sponge")
    }
}
