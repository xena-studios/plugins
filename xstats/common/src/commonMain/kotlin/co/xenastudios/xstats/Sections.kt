package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.text.Component
import co.xenastudios.xstats.api.Period
import co.xenastudios.xstats.api.ProfileLine
import co.xenastudios.xstats.api.ProfileSection
import co.xenastudios.xstats.data.Board
import co.xenastudios.xstats.store.Store
import kotlin.concurrent.Volatile

/**
 * Sections other plugins add to profiles through the API: xVote's votes, xBans' history. Each is
 * asked at once, with half a second to answer (on the JVM, [Extras.all]), so a slow or broken one
 * never holds up a profile.
 */
public class Sections(private val plugin: XStatsPlugin) {
    /** A section as registered. [key] is `<owner>_<id>`. */
    public class Section(public val owner: String, public val key: String, public val title: String, public val provider: ProfileSection)

    /** A section's lines for one profile. */
    public class Filled(public val key: String, public val title: String, public val lines: List<ProfileLine>)

    @Volatile
    private var sections: List<Section> = emptyList()
    private val failed = concurrentMap<String, Boolean>()

    public fun add(owner: String, id: String, title: String, provider: ProfileSection) {
        val key = owner.lowercase() + "_" + id
        locked(this) { sections = sections.filter { it.key != key } + Section(owner, key, title, provider) }
        failed.remove(key)
    }

    public fun remove(owner: String, id: String) {
        val key = owner.lowercase() + "_" + id
        locked(this) { sections = sections.filter { it.key != key } }
    }

    /** Takes away every section [owner] added, as it's disabled. */
    public fun removeAll(owner: String) {
        locked(this) { sections = sections.filter { it.owner != owner } }
    }

    public val isEmpty: Boolean get() = sections.isEmpty()

    /** Every section's lines for [player]. Blocks for up to half a second: never on a server thread. */
    public fun fill(player: PlayerRef, staff: Boolean): List<Filled> {
        val current = sections
        if (current.isEmpty()) return emptyList()
        val tasks = current.map { section -> Ask(section, player, staff) }
        val answers = plugin.extras?.all(tasks, TIMEOUT_MS) ?: tasks.map { task -> runCatching { task() }.getOrNull() }
        val filled = ArrayList<Filled>()
        for ((i, section) in current.withIndex()) {
            val lines = answers[i]
            if (lines == null) {
                if (failed.put(section.key, true) == null) plugin.log.warn("${section.owner}'s profile section ${section.key} failed or took over half a second. It's skipped where it does.")
                continue
            }
            if (lines.isNotEmpty()) filled += Filled(section.key, section.title, lines.toList())
        }
        return filled
    }

    private class Ask(private val section: Section, private val player: PlayerRef, private val staff: Boolean) : () -> List<ProfileLine> {
        override fun invoke(): List<ProfileLine> = section.provider.lines(player, staff)
    }

    public companion object {
        public const val TIMEOUT_MS: Long = 500
    }
}

/** What a board in the world shows: its title and the top of its leaderboard, in default-language, as everyone sees it at once. */
internal object Boards {
    fun text(plugin: XStatsPlugin, row: Store.BoardRow): Component {
        val texts = plugin.texts
        val language = texts.language(null)
        val stat = plugin.catalog.snapshot[row.stat]
            ?: return texts.render(language, XStatsMessages.boardNoStat, listOf("stat" to row.stat)) ?: Component.EMPTY
        val period = Period.parse(row.period) ?: Period.ALL
        val parts = ArrayList<Component>()
        texts.render(language, XStatsMessages.boardTitle, listOf("stat" to stat.name, "period" to Actions.periodName(plugin, null, period)))?.let(parts::add)
        val board: Board? = plugin.engine.leaderboards[row.stat, period]
        val entries = board?.top(plugin.config[co.xenastudios.xstats.config.XStatsConfig.leaderboards.boards.lines], false).orEmpty()
        if (entries.isEmpty()) {
            texts.render(language, XStatsMessages.boardEmpty, emptyList())?.let(parts::add)
        } else {
            for (entry in entries) {
                texts.render(
                    language, XStatsMessages.boardLine,
                    listOf("rank" to entry.rank.toString(), "player" to entry.name, "value" to co.xenastudios.xstats.stat.Format.value(stat, entry.value)),
                )?.let(parts::add)
            }
        }
        val children = ArrayList<Component>(parts.size * 2)
        for ((i, part) in parts.withIndex()) {
            if (i > 0) children += Component.text("\n")
            children += part
        }
        return Component(children = children)
    }
}
