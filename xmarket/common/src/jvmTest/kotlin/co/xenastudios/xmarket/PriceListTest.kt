package co.xenastudios.xmarket

import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.Audit
import co.xenastudios.xmarket.price.ShopFiles
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The default price list: made from xmarket/prices/ exactly as shipped (with -PupdateGolden it's
 * written instead), every obtainable item of the newest version priced, and no buy-craft-sell loop
 * under any supported version's recipes.
 */
class PriceListTest {
    private val result = PriceSources.generator().generate()

    @Test
    fun everyObtainableItemHasAPrice() {
        assertEquals(emptyList(), result.unpriced, "items with no base price and no recipe from priced items: add them to base.txt or excluded.txt")
    }

    @Test
    fun theShippedFilesAreTheGeneratorsOwn() {
        val shops = PriceSources.shops
        if (PriceSources.update) Files.createDirectories(shops)
        val wanted = HashSet<String>()
        for (category in result.categories) {
            val file = shops.resolve("${category.id}.yml")
            wanted += file.fileName.toString()
            val text = ShopFiles.write(category, JvmFiles.HEADER)
            if (PriceSources.update) {
                Files.write(file, text.toByteArray())
                continue
            }
            assertTrue(Files.isRegularFile(file), "no $file: run ./gradlew :xmarket:common:jvmTest -PupdateGolden")
            val shipped = String(Files.readAllBytes(file))
            if (shipped != text) fail("$file isn't what xmarket/prices/ makes: run ./gradlew :xmarket:common:jvmTest -PupdateGolden and commit it")
        }
        if (PriceSources.update) Files.list(shops).use { s -> s.filter { it.fileName.toString() !in wanted }.forEach(Files::delete) }
    }

    @Test
    fun theShippedRecipesAreTheNewestVersionsAndTheProcesses() {
        // /shop audit reads these from the jar: the newest version's recipes, then what players do by hand.
        val text = "# What /shop audit checks loops against: Minecraft ${PriceSources.NEWEST}'s recipes (xmarket/prices/vanilla/), then xmarket/prices/processes.txt.\n" +
            String(Files.readAllBytes(PriceSources.root.resolve("vanilla/${PriceSources.NEWEST}/recipes.txt"))) +
            String(Files.readAllBytes(PriceSources.root.resolve("processes.txt")))
        val file = PriceSources.resources.resolve("recipes.txt")
        if (PriceSources.update) Files.write(file, text.toByteArray())
        else assertEquals(text, String(Files.readAllBytes(file)), "$file is stale: run ./gradlew :xmarket:common:jvmTest -PupdateGolden")
    }

    @Test
    fun noLoopUnderAnyVersionsRecipes() {
        val buy = HashMap<String, Money>()
        val sell = HashMap<String, Money>()
        for (c in result.categories) for (e in c.entries) {
            e.buy?.let { buy[e.item.key] = it }
            e.sell?.let { sell[e.item.key] = it }
        }
        for (version in PriceSources.versions) {
            val loops = Audit.loops(buy, sell, PriceSources.recipes(version) + PriceSources.processes)
            assertEquals(emptyList(), loops.map { it.toString() }, "loops under $version's recipes")
        }
    }

    @Test
    fun sellingNeverPaysMoreThanBuying() {
        for (c in result.categories) for (e in c.entries) {
            val b = e.buy ?: continue
            val s = e.sell ?: continue
            assertTrue(s < b, "${e.id} sells for $s and costs $b")
        }
    }

    @Test
    fun theJarListsEveryCategory() {
        assertEquals(result.categories.map { it.id }, JvmFiles.CATEGORIES)
    }
}
