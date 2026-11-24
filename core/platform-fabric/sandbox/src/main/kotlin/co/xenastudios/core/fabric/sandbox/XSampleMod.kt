package co.xenastudios.core.fabric.sandbox

import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.sample.SampleConfig
import co.xenastudios.core.sample.SampleMessages
import co.xenastudios.core.sample.SamplePermissions
import co.xenastudios.core.sample.XSample
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/**
 * xSample as a Fabric mod: the core's sample plugin on platform-fabric, never released. CI boots
 * it on a real server at the oldest and newest Minecraft lines (scripts/boot-fabric.sh): it
 * loads, its commands run from the console, `debug` writes its report, and the server stops
 * cleanly.
 *
 * Everything a real plugin's Fabric entry will do, in the same order: make the platform while
 * mods load, join the family (its folder, board and player cache), declare permissions and
 * commands (Minecraft registers commands when it builds its dispatcher, before the server
 * starts), turn modules on, and turn everything off as the server stops.
 */
public object XSampleMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform("xsample", "xSample")
        val family = platform.family
        // English from the code, read for each source's language: the plugin's lang/ files and
        // the family's look come with the plugins' own Fabric entry, which loads them from disk.
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
        // The update checker, as every plugin has it (xSample isn't on Modrinth, so it finds
        // nothing). bStats has no Fabric platform, so there's no metrics call here.
        platform.checkUpdates("xsample", sample.admin.updates, SampleConfig.updates.settings(SampleConfig.defaults)) { translations }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> sample.disable() }
        platform.log.info("Enabled on ${platform.name} (Kotlin ${KotlinVersion.CURRENT})")
    }
}
