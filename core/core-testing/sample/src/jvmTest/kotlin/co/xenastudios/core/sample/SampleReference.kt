package co.xenastudios.core.sample

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.testing.FakePlatform

/**
 * xSample's reference, for `./gradlew xenaManifest`: the plugin built on the fake platform, as
 * its tests build it, with no server and no one online.
 */
object SampleReference : ManifestSource {
    private object NoOne : OnlinePlayers {
        override fun find(name: String, viewer: CommandSource): PlayerRef? = null

        override fun names(viewer: CommandSource): List<String> = emptyList()
    }

    override val reference: PluginReference get() = XSample(FakePlatform(), NoOne).reference
}
