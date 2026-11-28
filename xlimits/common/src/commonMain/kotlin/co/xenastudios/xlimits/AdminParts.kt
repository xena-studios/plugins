package co.xenastudios.xlimits

import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PasteService

/** What `/xlimits debug` runs on, made the first time it runs: not as the server starts. */
internal object AdminParts {
    fun debug(plugin: XLimitsPlugin, about: () -> Map<String, String>, paste: PasteService?): Debug {
        val checks = listOf(HealthChecks.threading(plugin.platform), HealthChecks.modules(plugin.modules), HealthChecks.config { plugin.problems })
        return Debug(
            plugin.platform, "${XLimitsPlugin.NAME} ${plugin.version}", checks,
            about = {
                val f = plugin.server.features
                about() + mapOf(
                    "Limits" to plugin.limits().joinToString(", ").ifEmpty { "none" },
                    "Switches here" to f.switches.sorted().joinToString(", ") { Switch.NAMES[it] },
                    "Chunks counted" to plugin.chunks.size.toString(),
                    "Pets known" to plugin.pets.size.toString(),
                    "Profiles" to if (f.perRegion) "per region" else "server-wide",
                    "Farm slowing" to if (plugin.farmsPaused) "paused by /xlimits restore" else "on",
                    "Not on this server" to f.gaps.joinToString("; ").ifEmpty { "nothing" },
                )
            },
            errors = plugin.log, paste = paste,
        )
    }
}
