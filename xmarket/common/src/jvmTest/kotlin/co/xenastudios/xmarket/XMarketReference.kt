package co.xenastudios.xmarket

import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.manifest.EventInfo
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.common.manifest.PluginReference
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xmarket.api.event.ShopBoughtEvent
import co.xenastudios.xmarket.api.event.ShopBuyEvent
import co.xenastudios.xmarket.api.event.ShopSellEvent
import co.xenastudios.xmarket.api.event.ShopSoldEvent
import co.xenastudios.xmarket.config.XMarketConfig

/** xMarket's reference, for `./gradlew :xmarket:common:xenaManifest`: the plugin with its default config on the fake platform. */
object XMarketReference : ManifestSource {
    override val reference: PluginReference
        get() {
            val platform = FakePlatform()
            val server = FakeMarketServer(platform.scheduler)
            val loaded = Loaded(XMarketConfig.defaults, Translations.of(XMarketMessages.catalog, emptyMap()))
            val files = object : Files {
                override fun load() = loaded
                override fun shops(currency: co.xenastudios.xmarket.price.Currency) = Shops(emptyList())
                override fun save(category: co.xenastudios.xmarket.price.Category): String? = null
                override fun resource(name: String): String? = null
            }
            val plugin = XMarketPlugin(platform, server, files, server, server, importers = { importSources(it, java.nio.file.Paths.get("plugins"), files) { null } })
            platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
            return PluginReference(
                XMarketPlugin.PLUGIN_ID, XMarketPlugin.NAME, "A server shop, with sell and worth.",
                plugin.dispatcher.tree, listOf(plugin.permissions, plugin.admin.permissions),
                config = XMarketConfig,
                catalog = XMarketMessages.catalog,
                events = listOf(
                    EventInfo.of(ShopBuyEvent.TYPE, "A player is about to buy. Cancel it to refuse, or change the price.", since = "1.0.0"),
                    EventInfo.of(ShopSellEvent.TYPE, "A player is about to sell, one event per item in the sale. Cancel it to keep that item out, or change the price.", since = "1.0.0"),
                    EventInfo.of(ShopBoughtEvent.TYPE, "A player bought something and paid for it.", since = "1.0.0"),
                    EventInfo.of(ShopSoldEvent.TYPE, "A player sold something and was paid.", since = "1.0.0"),
                ),
            ).also { platform.scheduler.runAs(Where.Global) { plugin.stop() } }
        }
}
