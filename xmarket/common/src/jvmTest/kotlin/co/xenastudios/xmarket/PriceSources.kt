package co.xenastudios.xmarket

import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.Generator
import co.xenastudios.xmarket.price.RecipeBook
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** xmarket/prices/, as the build hands its path to the tests: the price list's sources. */
object PriceSources {
    val root: Path = Paths.get(checkNotNull(System.getProperty("xmarket.prices")) { "the build sets xmarket.prices" })
    val shops: Path = Paths.get(checkNotNull(System.getProperty("xmarket.shops")))

    /** The jar's own copies: what the plugin reads at runtime (unobtainable.txt, recipes.txt). */
    val resources: Path = shops.parent
    val update: Boolean = System.getProperty("xena.updateGolden") == "true"

    /** The newest version, whose items and recipes the price list is made from. */
    const val NEWEST = "26.3"

    /** Every version with its recipes here, oldest first. */
    val versions: List<String> = listOf("1.12.2", "1.13.2", "1.16.5", "1.20.1", "1.21.11", "26.3")

    fun lines(name: String): List<String> = Files.readAllLines(root.resolve(name)).map { it.substringBefore(" #").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun items(version: String): List<String> = lines("vanilla/$version/items.txt")

    // An older version's recipes in today's names (ItemAliases): its grass is today's short_grass.
    fun recipes(version: String): RecipeBook {
        var text = String(Files.readAllBytes(root.resolve("vanilla/$version/recipes.txt")))
        if (version != NEWEST) {
            for (old in listOf("grass", "scute", "chain", "sign", "cactus_green", "dandelion_yellow", "rose_red", "grass_path")) {
                val now = co.xenastudios.xmarket.price.ItemAliases.current("minecraft:$old").removePrefix("minecraft:")
                text = text.replace(Regex("(?<=[ |])$old(?=[ |*\n])"), now)
            }
        }
        return RecipeBook.parse(text)
    }

    val processes: RecipeBook by lazy { RecipeBook.parse(String(Files.readAllBytes(root.resolve("processes.txt")))) }

    val base: Map<String, Money> by lazy {
        lines("base.txt").associate { line ->
            val (key, price) = line.split(Regex("\\s+"), limit = 2)
            key to checkNotNull(Money.parse(price)) { "base.txt: $line" }
        }
    }

    fun generator(): Generator = Generator(
        items(NEWEST), base, recipes(NEWEST) + processes, Files.readAllLines(resources.resolve("unobtainable.txt")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet(), lines("sell-only.txt").toSet(),
        lines("enchantments.txt").map {
            val p = it.split(' ')
            Generator.Enchantment(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5])
        },
    )
}
