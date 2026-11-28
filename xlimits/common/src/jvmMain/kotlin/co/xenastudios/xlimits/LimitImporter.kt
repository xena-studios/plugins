package co.xenastudios.xlimits

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.ImportPage
import co.xenastudios.core.common.admin.Importer
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.RememberingYaml
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.config.FarmRule
import co.xenastudios.xlimits.config.LimitTypes
import co.xenastudios.xlimits.config.Profile
import co.xenastudios.xlimits.config.Trigger
import co.xenastudios.xlimits.config.XLimitsConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * One limit another plugin's config had, as xLimits' config writes it: `/xlimits import
 * farmcontrol` and `/xlimits import insights` turn each into a line of `config.yml`, keeping
 * everything else the owner set. Writing the same one twice changes nothing.
 */
public sealed class ImportedLimit(public val name: String, public val from: String) {
    /** A cap per chunk on entities: a group of xLimits', a type, or [types] as a group of the owner's. */
    public class EntityCap(name: String, from: String, public val cap: Int, public val types: List<String>?) : ImportedLimit(name, from)

    /** A farm slowing rule. */
    public class Farm(name: String, from: String, public val rule: FarmRule, public val types: List<String>?) : ImportedLimit(name, from)

    /** A cap per chunk on blocks: one block, or [ids] counted together. */
    public class BlockCap(name: String, from: String, public val cap: Int, public val ids: List<String>) : ImportedLimit(name, from)

    /** A load profile. */
    public class LoadProfile(name: String, from: String, public val profile: Profile) : ImportedLimit(name, from)

    /** What couldn't be carried over, and why: skipped, so the dry run lists it. */
    public class Unsupported(name: String, from: String, public val reason: String) : ImportedLimit(name, from)
}

/**
 * Reads FarmControl's and Insights' configs beside xLimits' folder, and writes what they limit
 * into xLimits' `config.yml` ([ConfigFile.edit]): run `/xlimits reload` afterwards to use it.
 */
public abstract class LimitImporter(
    id: String,
    displayName: String,
    private val config: ConfigFile,
) : Importer<ImportedLimit>(id, displayName) {
    private var read: List<ImportedLimit>? = null

    /** Every limit there, in the order the files have them. */
    protected abstract fun readAll(): List<ImportedLimit>

    protected abstract fun present(): Boolean

    private fun all(): List<ImportedLimit> = read ?: readAll().also { read = it }

    override fun detect(): Pending<Boolean> = pendingOf(present())

    override fun count(): Pending<Outcome<Long, String>> = pendingOf(
        try {
            read = null
            Outcome.Ok(all().size.toLong())
        } catch (e: Exception) {
            Outcome.Err("couldn't read $displayName's files: ${e.message}")
        },
    )

    override fun read(cursor: String?, limit: Int): Pending<Outcome<ImportPage<ImportedLimit>, String>> {
        val start = cursor?.toIntOrNull() ?: 0
        val list = all()
        val end = minOf(list.size, start + limit)
        return pendingOf(Outcome.Ok(ImportPage(list.subList(start, end).toList(), if (end < list.size) end.toString() else null)))
    }

    override fun describe(record: ImportedLimit): String = "$displayName's ${record.from}"

    override fun skip(record: ImportedLimit): String? = (record as? ImportedLimit.Unsupported)?.reason

    override fun write(record: ImportedLimit): Pending<Outcome<Unit, String>> {
        val c = XLimitsConfig
        val edited = config.edit {
            when (record) {
                is ImportedLimit.EntityCap -> {
                    this[c.entities.limits] = (this[c.entities.limits] ?: c.entities.limits.default) + (record.name to record.cap)
                    record.types?.let { this[c.entities.groups] = (this[c.entities.groups] ?: emptyMap()) + (record.name to it) }
                }
                is ImportedLimit.Farm -> {
                    this[c.farms.rules] = (this[c.farms.rules] ?: c.farms.rules.default) + (record.name to record.rule)
                    record.types?.let { this[c.entities.groups] = (this[c.entities.groups] ?: emptyMap()) + (record.name to it) }
                }
                is ImportedLimit.BlockCap -> {
                    this[c.blocks.limits] = (this[c.blocks.limits] ?: c.blocks.limits.default) + (record.name to record.cap)
                    if (record.ids != listOf(record.name)) this[c.blocks.groups] = (this[c.blocks.groups] ?: emptyMap()) + (record.name to record.ids)
                }
                is ImportedLimit.LoadProfile -> {
                    this[c.profiles.list] = (this[c.profiles.list] ?: c.profiles.list.default) + (record.name to record.profile)
                    this[c.profiles.enabled] = true
                }
                is ImportedLimit.Unsupported -> Unit
            }
        }
        return pendingOf(
            when (edited) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Err -> Outcome.Err(edited.error.message)
            },
        )
    }

    public companion object {
        /** Both importers, reading the plugins' folders in [plugins] and writing [plugin]'s config. */
        @JvmStatic
        public fun all(plugins: Path, plugin: XLimitsPlugin): List<Importer<*>> {
            val config = ConfigFile(XLimitsConfig, plugins.resolve("xLimits").resolve("config.yml"), RememberingYaml())
            return listOf(FarmControl(plugins.resolve("FarmControl"), config), Insights(plugins.resolve("Insights"), config))
        }

        internal fun yaml(file: Path): YamlNode.Mapping? {
            if (!Files.isRegularFile(file)) return null
            val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            return when (val node = RememberingYaml().read(text)) {
                is Outcome.Ok -> node.value as? YamlNode.Mapping
                is Outcome.Err -> throw IllegalArgumentException("${file.fileName} line ${node.error.line}: ${node.error.message}")
            }
        }

        internal fun YamlNode.Mapping.path(path: String): YamlNode? {
            var node: YamlNode = this
            for (part in path.split('.')) node = (node as? YamlNode.Mapping)?.get(part) ?: return null
            return node
        }

        internal fun YamlNode?.text(): String? = (this as? YamlNode.Scalar)?.content?.trim()

        internal fun YamlNode?.strings(): List<String> = when (this) {
            is YamlNode.Sequence -> items.mapNotNull { it.text() }
            is YamlNode.Scalar -> listOf(content.trim())
            else -> emptyList()
        }
    }
}

/**
 * FarmControl's profiles (`profiles.yml`) that its `config.yml` runs: each proactive profile's
 * group becomes a farm rule (its count the threshold) or, for `kill`, an entity cap one under it,
 * since xLimits refuses new mobs rather than killing; its reactive profiles become a load
 * profile on FarmControl's MSPT thresholds. FarmControl counts within a distance; xLimits counts
 * per chunk, so the numbers carry over as they are and the docs say to check them.
 */
public class FarmControl(private val folder: Path, config: ConfigFile) : LimitImporter("farmcontrol", "FarmControl", config) {
    override fun present(): Boolean = Files.isRegularFile(folder.resolve("profiles.yml"))

    override fun readAll(): List<ImportedLimit> {
        val profiles = yaml(folder.resolve("profiles.yml"))?.path("profiles") as? YamlNode.Mapping ?: return emptyList()
        val config = yaml(folder.resolve("config.yml"))
        val settings = config?.path("world-settings.default")
        val proactive = (settings as? YamlNode.Mapping)?.path("profiles.proactive").strings().toSet()
        val reactive = (settings as? YamlNode.Mapping)?.path("profiles.reactive").strings()
        val out = ArrayList<ImportedLimit>()
        for (entry in profiles.entries) {
            val name = entry.key
            if (name !in proactive) continue
            out += profile(name, entry.value as? YamlNode.Mapping ?: continue)
        }
        if (reactive.isNotEmpty()) {
            val s = settings as YamlNode.Mapping
            val on = s.path("reactive-mode-settings.trigger-mspt-threshold").text()?.toDoubleOrNull() ?: 45.0
            val off = s.path("reactive-mode-settings.untrigger-mspt-threshold").text()?.toDoubleOrNull() ?: 35.0
            out += ImportedLimit.LoadProfile(
                "farmcontrol-reactive", "reactive mode (${reactive.joinToString()})",
                Profile(Trigger.MSPT_ABOVE, on, off, REACTIVE_SCALE, emptyMap()),
            )
        }
        return out
    }

    private fun profile(name: String, section: YamlNode.Mapping): List<ImportedLimit> {
        val from = "profile $name"
        val group = section["group"] as? YamlNode.Mapping ?: return listOf(ImportedLimit.Unsupported(name, from, "it has no group"))
        val count = group["count"].text()?.toIntOrNull() ?: return listOf(ImportedLimit.Unsupported(name, from, "its group has no count"))
        val types = group["types"].strings()
        val categories = types.filter { it.startsWith("category:") }.map { CATEGORIES[it.removePrefix("category:").lowercase()] }
        val plain = types.filter { !it.startsWith("category:") }.map(LimitTypes::id)
        val (limit, groupTypes) = when {
            types.isEmpty() -> return listOf(ImportedLimit.Unsupported(name, from, "its group has no types"))
            null in categories -> return listOf(ImportedLimit.Unsupported(name, from, "it uses a category xLimits has no group for"))
            categories.size == 1 && plain.isEmpty() -> categories[0]!! to null
            categories.isEmpty() && plain.size == 1 -> plain[0] to null
            categories.isEmpty() -> LimitTypes.id(name) to plain
            else -> return listOf(ImportedLimit.Unsupported(name, from, "it mixes categories and types, which one xLimits group can't"))
        }
        val actions = section["actions"].strings().map { it.lowercase() }
        val out = ArrayList<ImportedLimit>()
        if ("kill" in actions) out += ImportedLimit.EntityCap(limit, from, maxOf(0, count - 1), groupTypes)
        val slow = actions.mapNotNull(ACTIONS::get).toSet()
        if (slow.isNotEmpty()) out += ImportedLimit.Farm(limit, from, FarmRule(count, slow), groupTypes)
        if (out.isEmpty()) out += ImportedLimit.Unsupported(name, from, "none of its actions (${actions.joinToString()}) is one xLimits has")
        return out
    }

    private companion object {
        const val REACTIVE_SCALE = 0.75
        val CATEGORIES = mapOf("animal" to "animals", "monster" to "monsters", "water_animal" to "water", "ambient" to "ambient", "villager" to "villagers")
        val ACTIONS = mapOf(
            "disable-collisions" to SlowAction.NO_COLLISIONS, "remove-awareness" to SlowAction.NO_AI, "remove-ai" to SlowAction.NO_AI,
            "remove-random-movement" to SlowAction.NO_AI, "disable-breeding" to SlowAction.NO_BREEDING,
        )
    }
}

/**
 * Insights' limits (each `.yml` file in its `limits` folder): each `GROUP` limit of plain materials becomes a block cap
 * (several materials a block group of the owner's), and its `entities` an entity cap. `TILE`
 * and `PERMISSION` limits and regular expressions have no xLimits equal, so they're listed as
 * skipped.
 */
public class Insights(private val folder: Path, config: ConfigFile) : LimitImporter("insights", "Insights", config) {
    override fun present(): Boolean = Files.isDirectory(folder.resolve("limits"))

    override fun readAll(): List<ImportedLimit> {
        val dir = folder.resolve("limits")
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".yml") }.sorted().collect(java.util.stream.Collectors.toList()) }
        val out = ArrayList<ImportedLimit>()
        for (file in files) {
            val limit = yaml(file)?.get("limit") as? YamlNode.Mapping ?: continue
            val from = "limit ${file.fileName}"
            val name = LimitTypes.id(limit["name"].text() ?: file.fileName.toString().removeSuffix(".yml"))
            val type = limit["type"].text()?.uppercase() ?: "GROUP"
            val cap = limit["limit"].text()?.toIntOrNull()
            when {
                cap == null || cap < 0 -> out += ImportedLimit.Unsupported(name, from, "it has no limit")
                type != "GROUP" -> out += ImportedLimit.Unsupported(name, from, "$type limits have no xLimits equal")
                limit["regex"].text() == "true" -> out += ImportedLimit.Unsupported(name, from, "it matches by regular expression")
                else -> {
                    val materials = limit["materials"].strings().map(LimitTypes::id)
                    val entities = limit["entities"].strings().map(LimitTypes::id)
                    if (materials.isNotEmpty()) out += ImportedLimit.BlockCap(if (materials.size == 1) materials[0] else name, from, cap, materials)
                    if (entities.isNotEmpty()) {
                        out += ImportedLimit.EntityCap(if (entities.size == 1) entities[0] else name, from, cap, entities.takeIf { it.size > 1 })
                    }
                    if (materials.isEmpty() && entities.isEmpty()) out += ImportedLimit.Unsupported(name, from, "it lists no materials or entities")
                }
            }
        }
        return out
    }
}
