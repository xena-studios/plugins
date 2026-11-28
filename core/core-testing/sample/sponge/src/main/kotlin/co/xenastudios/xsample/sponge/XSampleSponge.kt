package co.xenastudios.xsample.sponge

import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.sample.SampleConfig
import co.xenastudios.core.sample.SampleMessages
import co.xenastudios.core.sample.SamplePermissions
import co.xenastudios.core.sample.XSample
import co.xenastudios.core.sponge.ServerStoppingEvent
import co.xenastudios.core.sponge.SpongeBootstrap
import co.xenastudios.core.sponge.SpongePlatform
import com.google.inject.Inject
import org.spongepowered.plugin.PluginContainer

/**
 * xSample as a Sponge plugin: the core's sample plugin on platform-sponge, never released. CI
 * boots it on SpongeVanilla at API 8 (1.16.5) and the newest (scripts/boot-sponge.sh): it loads,
 * its commands run from the console, `debug` writes its report, and the server stops cleanly.
 *
 * Everything a real plugin's Sponge entry does, in the same order, as Sponge constructs it: make
 * the platform, join the family (its folder, board and player cache), declare permissions and
 * commands (Sponge takes them as the server starts), turn modules on, and turn everything off as
 * the server stops.
 */
public class XSampleSponge @Inject constructor(container: PluginContainer) : SpongeBootstrap(container) {
    init {
        val platform = SpongePlatform(this, "xSample", "1.0.0")
        val family = platform.family
        // English from the code, read for each source's language.
        val translations = Translations.of(SampleMessages.catalog, emptyMap())
        val sample = XSample(
            platform, platform.players,
            lookup = family.players.asLookup(),
            registrar = platform.commands,
            about = platform::about,
            adminText = AdminText.of(translations, platform::locale),
        )
        platform.permissions.sync(SamplePermissions.permissions + sample.admin.permissions.permissions)
        sample.enable()
        // The update checker, as every plugin has it (xSample isn't on Modrinth, so it finds nothing).
        platform.checkUpdates("xsample", sample.admin.updates, SampleConfig.updates.settings(SampleConfig.defaults)) { translations }
        platform.events.subscribe(ServerStoppingEvent.TYPE) { sample.disable() }
        platform.log.info("Enabled on ${platform.name} (Kotlin ${KotlinVersion.CURRENT})")
    }
}
