package co.xenastudios.xmoney

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xmoney.api.event.PreTransactionEvent
import co.xenastudios.xmoney.config.XMoneyConfig

/**
 * xMoney's reference, for `./gradlew :xmoney:common:xenaManifest`: the plugin with its default
 * config on the fake platform, as its tests build it.
 */
object XMoneyReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val server = FakeMoneyServer()
            val platform = FakePlatform()
            val loaded = Loaded(XMoneyConfig.defaults, Translations.of(XMoneyMessages.catalog, emptyMap()))
            val plugin = XMoneyPlugin(platform, server, { loaded }, server)
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XMoneyPlugin.PLUGIN_ID, XMoneyPlugin.NAME, "The economy, and Vault, in one plugin.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XMoneyConfig,
                placeholders = plugin.placeholders.set(),
                catalog = XMoneyMessages.catalog,
                events = listOf(
                    EventInfo.of(PreTransactionEvent.TYPE, "A balance is about to change. Cancel it to refuse the change.", since = "1.0.0"),
                    EventInfo.of(BalanceChangedEvent.TYPE, "A balance changed, and the change is saved.", since = "1.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
