package co.xenastudios.xmarket

import co.xenastudios.core.common.admin.ImportCounts
import co.xenastudios.core.common.admin.ImportListener
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.xmarket.api.ItemKey
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import java.nio.file.Files as NioFiles
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** EconomyShopGUI's, ShopGUI+'s and EssentialsX's files imported as categories. */
class ImportTest {
    private val folder = NioFiles.createTempDirectory("xmarket-import")
    private val plugins = folder.resolve("plugins")
    private val platform = FakePlatform()
    private val server = FakeMarketServer(platform.scheduler)
    private val written = ArrayList<Category>()
    private val files = object : Files {
        override fun load(): Loaded = Loaded(co.xenastudios.xmarket.config.XMarketConfig.defaults, co.xenastudios.core.common.i18n.Translations.of(XMarketMessages.catalog, emptyMap()))
        override fun shops(currency: Currency): Shops = Shops(emptyList())
        override fun save(category: Category): String? = null.also { written += category }
        override fun resource(name: String): String? = null
    }
    private val materials = MaterialNames { name ->
        val n = name.lowercase()
        if (n == "not_a_thing") null else ItemKey.namespaced(n.substringBefore(':').let { if (it == "wool" && n.endsWith(":14")) "red_wool" else it })
    }
    private val plugin = XMarketPlugin(platform, server, files, server, server, importers = { importSources(it, plugins, files, materials) })

    init {
        platform.scheduler.runAs(Where.Global) { plugin.start(files.load()) }
        plugin.loadPrices()
    }

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private fun write(path: String, text: String) {
        val f: Path = plugins.resolve(path)
        NioFiles.createDirectories(f.parent)
        NioFiles.write(f, text.trimIndent().toByteArray())
    }

    private var done: ImportCounts? = null

    private fun import(id: String, dryRun: Boolean = false): ImportCounts {
        done = null
        val listener = object : ImportListener {
            override fun notFound() = error("not found")
            override fun started(total: Long, done: Long, resumed: Boolean) {}
            override fun progress(done: Long, total: Long) {}
            override fun recordFailed(record: String, reason: String) {}
            override fun finished(counts: ImportCounts, dryRun: Boolean) {
                done = counts
            }
            override fun stopped(reason: String) = error(reason)
        }
        val imports = importSources(plugin, plugins, files, materials)
        assertTrue(imports.start(id, dryRun, listener))
        repeat(50) { platform.scheduler.runPending(); platform.scheduler.tick() }
        return assertNotNull(done)
    }

    @Test
    fun economyShopGuisSectionsAndShops() {
        write("EconomyShopGUI/sections/Ores.yml", "enable: true\nslot: 3\ntitle: '&bOres'\n")
        write(
            "EconomyShopGUI/shops/Ores.yml",
            """
            pages:
              page1:
                items:
                  '1':
                    material: DIAMOND
                    buy: 150
                    sell: 40
                  '2':
                    material: NOT_A_THING
                    buy: 1
                  '3':
                    material: ENCHANTED_BOOK
                    enchantments: ["DAMAGE_ALL:5"]
                    buy: 900
                    sell: -1
                    limit: 5
            """,
        )
        val dry = import("economyshopgui", dryRun = true)
        assertEquals(3, dry.read)
        assertTrue(written.isEmpty(), "a dry run writes nothing")
        val counts = import("economyshopgui")
        assertEquals(2, counts.imported)
        val category = written.last()
        assertEquals("economyshopgui-ores", category.id)
        assertEquals("<aqua>Ores", category.name)
        assertEquals(Money.of(150), plugin.prices.find(ItemKey.of("diamond"), TradeKind.BUY)?.entry?.buy)
        val book = category.entries.first { it.item.id == "minecraft:enchanted_book" }
        assertEquals(mapOf("minecraft:sharpness" to 5), book.item.enchantments)
        assertEquals(null, book.sell, "-1 is no sell price")
    }

    @Test
    fun shopGuiPlusItemsAndCommands() {
        write(
            "ShopGUIPlus/shops/blocks.yml",
            """
            blocks:
              name: "&8Blocks"
              items:
                '1':
                  type: item
                  item:
                    material: WOOL
                    damage: 14
                    quantity: 16
                  buyPrice: 32
                  sellPrice: 8
                '2':
                  type: command
                  item:
                    material: PAPER
                    name: "&6VIP"
                  commands: ["lp user %player% parent add vip"]
                  buyPrice: 5000
            """,
        )
        assertEquals(2, import("shopguiplus").imported)
        val c = written.last()
        val wool = c.entries.first()
        assertEquals("minecraft:red_wool", wool.item.id)
        assertEquals(16, wool.amount)
        assertEquals(listOf("lp user %player% parent add vip"), c.entries.last().commands)
    }

    @Test
    fun essentialsWorthAsSellPrices() {
        write("Essentials/worth.yml", "worth:\n  diamond: 25.5\n  wool:\n    '0': 2\n")
        assertEquals(2, import("essentials").imported)
        assertEquals(Money.parse("25.5"), written.last().entries.first { it.item.id == "minecraft:diamond" }.sell)
    }
}
