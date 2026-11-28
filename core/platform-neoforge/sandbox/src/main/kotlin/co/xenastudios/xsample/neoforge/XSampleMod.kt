package co.xenastudios.xsample.neoforge

import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.neoforge.NeoForgePlatform
import co.xenastudios.core.sample.SampleConfig
import co.xenastudios.core.sample.SampleMessages
import co.xenastudios.core.sample.SamplePermissions
import co.xenastudios.core.sample.XSample
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppingEvent

/**
 * xSample as a NeoForge mod: the core's sample plugin on platform-neoforge, never released. CI
 * boots it on a real server at the oldest and newest Minecraft lines (scripts/boot-neoforge.sh):
 * it loads, its commands run from the console, `debug` writes its report, and the server stops
 * cleanly.
 *
 * Everything a real plugin's NeoForge entry will do, in the same order as on Fabric: make the
 * platform while mods load, join the family (its folder, board and player cache), declare
 * permissions and commands (Minecraft registers commands when it builds its dispatcher, before
 * the server starts), turn modules on, and turn everything off as the server stops.
 */
@Mod("xsample")
public class XSampleMod {
    // NeoForge makes every mod at once, on several threads, before Minecraft is set up: the
    // plugin starts at common setup instead, on the main thread (NeoForgePlatform.whenSetUp).
    init {
        NeoForgePlatform.whenSetUp("xsample") { start() }
    }

    private fun start() {
        val platform = NeoForgePlatform("xsample", "xSample")
        val family = platform.family
        // English from the code, read for each source's language: the plugin's lang/ files and
        // the family's look come with the plugins' own NeoForge entry, which loads them from disk.
        val translations = Translations.of(SampleMessages.catalog, emptyMap())
        val sample = XSample(
            platform, platform.players,
            lookup = family.players.asLookup(),
            registrar = platform.commands,
            about = platform::about,
            adminText = AdminText.of(translations, platform::locale),
            // The chat format, through NeoForge's ServerChatEvent.
            chat = { platform.chat() },
        )
        platform.permissions.sync(SamplePermissions.permissions + sample.admin.permissions.permissions)
        sample.enable()
        // The update checker, as every plugin has it (xSample isn't on Modrinth, so it finds
        // nothing). bStats has no NeoForge platform, so there's no metrics call here.
        platform.checkUpdates("xsample", sample.admin.updates, SampleConfig.updates.settings(SampleConfig.defaults)) { translations }
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent::class.java) { _ -> sample.disable() }
        //? if !forge {
        // The world hooks' mixins on this Minecraft line, once the server runs (WorldCheck).
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, net.neoforged.neoforge.event.server.ServerStartedEvent::class.java) { event ->
            WorldCheck.run(platform, event.server)
        }
        //?}
        platform.log.info("Enabled on ${platform.name} (Kotlin ${KotlinVersion.CURRENT})")
    }
}
