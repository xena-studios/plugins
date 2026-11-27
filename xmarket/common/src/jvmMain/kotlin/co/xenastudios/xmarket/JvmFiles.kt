package co.xenastudios.xmarket

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.JvmYaml
import co.xenastudios.core.jvm.config.RememberingYaml
import co.xenastudios.core.jvm.i18n.LangFolder
import co.xenastudios.xmarket.config.XMarketConfig
import co.xenastudios.xmarket.price.Category
import co.xenastudios.xmarket.price.Currency
import co.xenastudios.xmarket.price.ShopFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.Files as NioFiles

/**
 * xMarket's files on a JVM server: the family's `config.yml`, then `config.yml` and `lang/` in
 * the plugin's [folder], and `shops/`, one file per category, which the jar's price list fills
 * the first time (when there's no `shops/` folder at all, so a category an owner deleted stays
 * deleted). [resource] reads a file the jar ships.
 */
public class JvmFiles(
    private val folder: Path,
    private val family: Family,
    private val shipped: Map<LanguageTag, String>,
    private val log: Log,
    private val resources: (String) -> String?,
) : Files {
    private val path = folder.resolve("config.yml")
    private val yaml = RememberingYaml()
    private val file = ConfigFile(XMarketConfig, path, yaml)
    private val shops = folder.resolve("shops")

    @Volatile
    private var last: Config? = null

    override fun load(): Loaded {
        val config = config()
        return translations(config.config, config.problems)
    }

    /** `config.yml` alone: what the server's startup reads as the plugin enables. */
    public fun config(): Loaded {
        val problems = ArrayList<String>()
        val config = when (val loaded = file.load()) {
            is Outcome.Ok -> {
                loaded.value.notices.forEach { log.info("config.yml: ${it.message}") }
                loaded.value.problems.forEach { problems += it.describe("config.yml") }
                loaded.value.config
            }
            is Outcome.Err -> {
                problems += "${loaded.error}; ${if (last == null) "using the defaults" else "keeping the settings from before"} until it's fixed."
                last ?: XMarketConfig.defaults
            }
        }
        last = config
        yaml.forget()
        return Loaded(config, Translations.of(XMarketMessages.catalog, emptyMap()), problems)
    }

    /** The family's settings and `lang/`, every shipped language written in full, the owner's edits kept. Logs every problem. */
    public fun translations(config: Config, configProblems: List<String> = emptyList()): Loaded {
        val problems = ArrayList(configProblems)
        val familyLoad = family.load()
        familyLoad.error?.let { problems += it.message }
        familyLoad.problems.forEach { problems += it.describe("plugins/Xena/config.yml") }
        familyLoad.notices.forEach { log.info("plugins/Xena/config.yml: ${it.message}") }
        val look = family.look(config, XMarketConfig.messages)
        val lang = LangFolder(folder, XMarketMessages.catalog, shipped).load(look.languages, look.theme)
        lang.notices.forEach { log.info(it.message) }
        lang.problems.forEach { problems += it.message }
        problems.forEach(log::warn)
        return Loaded(config, lang.translations, problems)
    }

    override fun shops(currency: Currency): Shops {
        val problems = ArrayList<String>()
        if (!NioFiles.isDirectory(shops)) {
            NioFiles.createDirectories(shops)
            var written = 0
            for (id in CATEGORIES) {
                val text = resources("xmarket/shops/$id.yml") ?: continue
                write(shops.resolve("$id.yml"), text)
                written++
            }
            log.info("Wrote the price list to shops/: $written categories, one file each.")
        }
        val categories = ArrayList<Category>()
        val files = NioFiles.list(shops).use { s -> s.filter { it.fileName.toString().endsWith(".yml") }.sorted().collect(java.util.stream.Collectors.toList()) }
        for (f in files) {
            val id = f.fileName.toString().removeSuffix(".yml")
            if (!ID.matches(id)) {
                problems += "shops/${f.fileName}: a category's file name is lower-case a-z, 0-9, - and _, so it's left out."
                continue
            }
            val text = try {
                String(NioFiles.readAllBytes(f), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                problems += "Couldn't read shops/${f.fileName}: $e"
                continue
            }
            when (val node = JvmYaml.read(text)) {
                is Outcome.Ok -> {
                    val read = ShopFiles.read(id, node.value, currency)
                    problems += read.problems
                    read.category?.let { categories += it }
                }
                is Outcome.Err -> problems += "shops/${f.fileName} line ${node.error.line}: ${node.error.message}; the category is left out until it's fixed."
            }
        }
        return Shops(categories, problems)
    }

    override fun save(category: Category): String? = try {
        NioFiles.createDirectories(shops)
        write(shops.resolve("${category.id}.yml"), ShopFiles.write(category, HEADER))
        null
    } catch (e: Exception) {
        e.toString()
    }

    override fun resource(name: String): String? = resources(name)

    private fun write(target: Path, text: String) {
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        NioFiles.write(temp, text.toByteArray(StandardCharsets.UTF_8))
        try {
            NioFiles.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            NioFiles.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    public companion object {
        /** The languages the jar ships besides English: drafts until native speakers have read them. */
        @JvmField
        public val LANGUAGES: List<LanguageTag> = listOf("de", "es", "fr", "pt-BR", "ru", "pl", "zh-CN").map { LanguageTag.of(it) }

        /** The price list's categories, as the jar ships them in `xmarket/shops/`. */
        @JvmField
        public val CATEGORIES: List<String> = listOf(
            "building", "wood", "colors", "nature", "ores", "farming", "mobs", "redstone", "tools", "armor",
            "brewing", "enchanting", "nether", "end", "decoration", "misc",
        )

        /** The header a category's file is written with. */
        @JvmField
        public val HEADER: List<String> = listOf(
            "One of xMarket's categories: its items, what each costs to buy and what it sells for.",
            "Made from xMarket's price list, where every crafted item costs what its recipe does,",
            "so nothing can be bought, crafted and sold for a profit (/shop audit checks your edits).",
            "Prices are for one item, before the multiplier in config.yml. Leave buy or sell out to",
            "stop an item being bought or sold. https://plugins.xenastudios.co/xmarket/price-list/",
        )

        private val ID = Regex("[a-z0-9][a-z0-9_-]*")

        /** A [resources] function that reads from [loader]: for a Bukkit plugin, its own class loader. */
        @JvmStatic
        public fun classpath(loader: ClassLoader): (String) -> String? = { name ->
            loader.getResourceAsStream(name)?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        }
    }
}
