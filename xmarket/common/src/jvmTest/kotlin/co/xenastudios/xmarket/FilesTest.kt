package co.xenastudios.xmarket

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.testing.FakeFamily
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.api.TradeKind
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.ShopFiles
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Category files read and written with the YAML parser plugins use, and the shipped price list. */
class FilesTest {
    private val folder = Files.createTempDirectory("xmarket-files")

    @AfterTest
    fun clean() {
        folder.toFile().deleteRecursively()
    }

    private val file = """
        name: "<gold>Ranks & kits"
        icon: nether_star
        slot: 4
        currency: item:diamond
        permission: shop.vip
        requires: ["%player_level% >= 10"]
        items:
          diamond: {buy: 120, sell: 30.5}
          "potion{swiftness}": {buy: 12}
          vip:
            icon: nether_star
            name: "<gold>VIP"
            lore: ["Forever"]
            buy: 5000
            commands: ["lp user {player} parent add vip"]
            limit: {buy: 1, per: ever}
          sword:
            item: diamond_sword
            data: |
              item:
                ==: org.bukkit.inventory.ItemStack
                type: DIAMOND_SWORD
            buy: 300
            server-limit: {buy: 10, per: week}
          bad: {buy: lots}
    """.trimIndent()

    @Test
    fun aCategoryReadsAndWritesBackTheSame() {
        val read = ShopFiles.read("ranks", (JvmYaml.read(file) as Outcome.Ok).value)
        val c = assertNotNull(read.category)
        assertEquals(listOf("diamond", "potion{swiftness}", "vip", "sword", "bad"), c.entries.map { it.id })
        assertTrue(read.problems.any { "bad's buy price" in it }, read.problems.toString())
        assertEquals(Currency.Items(co.xenastudios.xmarket.price.ItemSpec("minecraft:diamond")), c.currency)
        assertEquals(Money.parse("30.5"), c.entry("diamond")!!.sell)
        assertEquals("swiftness", c.entry("potion{swiftness}")!!.item.potion)
        assertEquals("ever", c.entry("vip")!!.limits.single().period.word)
        assertTrue(c.entry("sword")!!.item.data!!.contains("DIAMOND_SWORD"))
        assertTrue(c.entry("sword")!!.limits.single().server)
        val again = ShopFiles.read("ranks", (JvmYaml.read(ShopFiles.write(c, listOf("a header"))) as Outcome.Ok).value).category!!
        assertEquals(ShopFiles.write(c), ShopFiles.write(again))
    }

    @Test
    fun theFirstStartWritesTheShippedPriceListAndReadsItBack() {
        val platform = FakePlatform()
        val files = JvmFiles(folder, platform.family.family(), emptyMap(), RecordingLog(), JvmFiles.classpath(javaClass.classLoader))
        val shops = files.shops(Currency.Money)
        assertEquals(emptyList(), shops.problems)
        assertEquals(JvmFiles.CATEGORIES.sorted(), shops.categories.map { it.id }.sorted())
        val made = PriceSources.generator().generate().categories.associate { it.id to it.entries.size }
        assertEquals(made, shops.categories.associate { it.id to it.entries.size }, "every entry of the shipped files reads back")
        assertTrue(Files.isRegularFile(folder.resolve("shops/ores.yml")))
        val list = co.xenastudios.xmarket.price.PriceList(shops.categories)
        assertEquals(Money.of(200), list.find(co.xenastudios.xmarket.api.ItemKey.of("diamond"), TradeKind.BUY)!!.entry.buy)
        // A category deleted stays deleted: the folder is there now.
        Files.delete(folder.resolve("shops/misc.yml"))
        assertTrue(files.shops(Currency.Money).categories.none { it.id == "misc" })
        // An edit is written whole, and read back.
        val ores = shops.categories.first { it.id == "ores" }
        assertEquals(null, files.save(ores.withEntries(ores.entries.take(2))))
        assertEquals(2, files.shops(Currency.Money).categories.first { it.id == "ores" }.entries.size)
    }

    @Test
    fun theJarShipsTheAuditsFiles() {
        val read = JvmFiles.classpath(javaClass.classLoader)
        assertTrue(read("xmarket/recipes.txt")!!.contains("c diamond_block 1 diamond*9"))
        assertTrue(read("xmarket/unobtainable.txt")!!.lines().contains("bedrock"))
    }

    @Suppress("unused")
    private val unused: Category? = null
}
