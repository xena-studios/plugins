package co.xenastudios.zchat

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeChat
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.zchat.api.event.ChatMessageEvent
import co.xenastudios.zchat.api.event.ChatMessageSentEvent
import co.xenastudios.zchat.api.event.FilterMatchEvent
import co.xenastudios.zchat.api.event.MentionEvent
import co.xenastudios.zchat.api.event.PrivateMessageEvent
import co.xenastudios.zchat.config.ZChatConfig

/**
 * zChat's reference, for `./gradlew :zchat:common:xenaManifest`: the plugin with its default
 * config on the fake platform, as its tests build it, with no one online.
 */
object ZChatReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val server = FakeServer()
            val platform = FakePlatform()
            val loaded = Loaded(ZChatConfig.defaults, Translations.of(ZChatMessages.catalog, emptyMap()))
            val files = object : Files {
                override fun load() = loaded
                override fun editWords(word: String, add: Boolean) = co.xenastudios.core.common.Outcome.Ok(false)
            }
            val plugin = ZChatPlugin(platform, server, files, server, { FakeChat(platform.log) })
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                ZChatPlugin.PLUGIN_ID, ZChatPlugin.NAME, "Format your chat. Keep it clean.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = ZChatConfig,
                catalog = ZChatMessages.catalog,
                events = listOf(
                    EventInfo.of(ChatMessageEvent.TYPE, "A chat message passed zChat's checks and is about to be drawn. Cancel it to stop it, or add tags for the format.", since = "4.0.0"),
                    EventInfo.of(ChatMessageSentEvent.TYPE, "A chat message zChat handled went out, after every plugin had its say.", since = "4.0.0"),
                    EventInfo.of(MentionEvent.TYPE, "A mentioned player is about to hear the mention sound. Cancel it to keep it quiet.", since = "4.0.0"),
                    EventInfo.of(PrivateMessageEvent.TYPE, "A private message is about to be delivered. Cancel it, or change the message.", since = "4.0.0"),
                    EventInfo.of(FilterMatchEvent.TYPE, "The filter caught something in a chat or private message.", since = "4.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
