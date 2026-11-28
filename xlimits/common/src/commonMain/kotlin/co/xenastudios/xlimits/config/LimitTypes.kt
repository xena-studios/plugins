package co.xenastudios.xlimits.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.xlimits.api.SlowAction

/** The config's sections of things by name, one class told apart by its kind rather than an object each. */
internal object LimitTypes {
    /** `limits:` sections: a name and a whole number each. */
    val LIMITS: ConfigType<Map<String, Int>> = MapType(0)

    /** `groups:`: a name and a list of ids each. */
    val GROUPS: ConfigType<Map<String, List<String>>> = MapType(1)

    /** `farms.rules`. */
    val FARMS: ConfigType<Map<String, FarmRule>> = MapType(2)

    /** `worlds.overrides`: a world and its `limits:`. */
    val WORLD_LIMITS: ConfigType<Map<String, Map<String, Int>>> = MapType(3)

    /** `profiles.list`. */
    val PROFILES: ConfigType<Map<String, Profile>> = MapType(4)

    /** An entity, block or group name as the config's keys and lists hold it: lower case, no `minecraft:`. */
    fun id(raw: String): String = raw.trim().lowercase().removePrefix("minecraft:").replace('-', '_').replace(' ', '_')

    private val ACTIONS = mapOf("no-collisions" to SlowAction.NO_COLLISIONS, "no-ai" to SlowAction.NO_AI, "no-breeding" to SlowAction.NO_BREEDING)
    private val TRIGGERS = mapOf("players-online-above" to Trigger.PLAYERS_ONLINE_ABOVE, "tps-below" to Trigger.TPS_BELOW, "mspt-above" to Trigger.MSPT_ABOVE)

    fun actionName(action: SlowAction): String = ACTIONS.entries.first { it.value == action }.key

    fun triggerName(trigger: Trigger): String = TRIGGERS.entries.first { it.value == trigger }.key

    private class MapType<V : Any>(private val kind: Int) : ConfigType<Map<String, V>>() {
        override val name: String
            get() = when (kind) {
                0 -> "limits"
                1 -> "groups"
                2 -> "farm rules"
                3 -> "world limits"
                else -> "profiles"
            }

        override fun empty(): Map<String, V> = emptyMap()

        @Suppress("UNCHECKED_CAST")
        override fun decode(node: YamlNode, context: ConfigContext): Outcome<Map<String, V>, String> {
            if (node is YamlNode.Null) return Outcome.Ok(emptyMap())
            if (node !is YamlNode.Mapping) return Outcome.Err("should be a section of $name by name")
            val out = LinkedHashMap<String, V>()
            for (entry in node.entries) {
                val key = id(entry.key)
                if (key.isEmpty()) continue
                val value: Any = when (kind) {
                    0 -> count(entry.value)
                    1 -> ids(entry.value)
                    2 -> farm(entry.value)
                    3 -> (entry.value as? YamlNode.Mapping)?.let { limits(it) }
                    else -> profile(entry.value)
                } ?: continue
                // A world's name keeps its case: Bukkit's folders are case-sensitive.
                out[if (kind == 3) entry.key.trim() else key] = value as V
            }
            return Outcome.Ok(out)
        }

        override fun encode(value: Map<String, V>): YamlNode = YamlNode.Mapping(
            value.map { (name, item) -> YamlNode.Mapping.Entry(name, encodeOne(item)) },
        )

        @Suppress("UNCHECKED_CAST")
        private fun encodeOne(item: Any): YamlNode = when (item) {
            is Int -> YamlNode.Scalar(item.toString())
            is List<*> -> YamlNode.Sequence(item.map { YamlNode.Scalar(it.toString()) })
            is FarmRule -> mapping(
                "threshold" to YamlNode.Scalar(item.threshold.toString()),
                "actions" to YamlNode.Sequence(SlowAction.entries.filter { it in item.actions }.map { YamlNode.Scalar(actionName(it)) }),
            )
            is Profile -> mapping(
                "when" to YamlNode.Scalar(triggerName(item.trigger)),
                "on" to YamlNode.Scalar(number(item.on)),
                "off" to YamlNode.Scalar(number(item.off)),
                "scale" to YamlNode.Scalar(number(item.scale)),
                "limits" to limitsNode(item.limits),
            )
            else -> limitsNode(item as Map<String, Int>)
        }

        private fun limitsNode(limits: Map<String, Int>): YamlNode =
            YamlNode.Mapping(limits.map { YamlNode.Mapping.Entry(it.key, YamlNode.Scalar(it.value.toString())) })

        private fun mapping(vararg fields: Pair<String, YamlNode>): YamlNode =
            YamlNode.Mapping(fields.map { YamlNode.Mapping.Entry(it.first, it.second) })

        private fun number(value: Double): String =
            if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e12) value.toLong().toString() else value.toString()

        private fun count(node: YamlNode): Int? = (node as? YamlNode.Scalar)?.content?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

        private fun ids(node: YamlNode): List<String>? = when (node) {
            is YamlNode.Sequence -> node.items.mapNotNull { (it as? YamlNode.Scalar)?.content?.let(::id)?.takeIf(String::isNotEmpty) }
            is YamlNode.Scalar -> listOf(id(node.content)).filter(String::isNotEmpty)
            else -> null
        }

        private fun limits(section: YamlNode.Mapping): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            for (entry in section.entries) count(entry.value)?.let { out[id(entry.key)] = it }
            return out
        }

        private fun farm(node: YamlNode): FarmRule? {
            val section = node as? YamlNode.Mapping ?: return null
            val threshold = section["threshold"]?.let(::count) ?: return null
            val actions = when (val list = section["actions"]) {
                is YamlNode.Sequence -> list.items.mapNotNull { (it as? YamlNode.Scalar)?.content?.trim()?.lowercase()?.let(ACTIONS::get) }
                is YamlNode.Scalar -> listOfNotNull(ACTIONS[list.content.trim().lowercase()])
                else -> listOf(SlowAction.NO_COLLISIONS)
            }.toSet()
            if (actions.isEmpty()) return null
            return FarmRule(threshold, actions)
        }

        private fun profile(node: YamlNode): Profile? {
            val section = node as? YamlNode.Mapping ?: return null
            val trigger = (section["when"] as? YamlNode.Scalar)?.content?.trim()?.lowercase()?.let(TRIGGERS::get) ?: return null
            fun double(key: String): Double? = (section[key] as? YamlNode.Scalar)?.content?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val on = double("on") ?: return null
            val off = double("off") ?: on
            val scale = double("scale") ?: 1.0
            val limits = (section["limits"] as? YamlNode.Mapping)?.let { limits(it) }.orEmpty()
            return Profile(trigger, on, off, scale.coerceIn(0.0, 10.0), limits)
        }
    }
}
