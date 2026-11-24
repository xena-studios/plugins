package co.xenastudios.core.jvm.family

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.common.platform.SystemClock
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration

/**
 * One plugin's side of [CopiesShareTheBoardTest], loaded once per copy of the core: what it's
 * called with and returns is the JDK's only, as between two real plugins.
 */
@Suppress("unused") // Called by reflection, from another class loader.
class FamilyProbe(root: ConcurrentMap<String, Any>, plugin: String) {
    private val cache = PlayerCache(JvmFamilyBoard(root), PlayerStore.None, Inline, SystemClock, Quiet, plugin)

    fun joined(uuid: String, name: String, locale: String) = cache.joined(PlayerRef.parseOrNull(uuid)!!, name, locale)

    fun vanish(uuid: String) = cache.setVanished(PlayerRef.parseOrNull(uuid)!!, true)

    /** `name|locale|vanished`, or `null` if the player isn't known. */
    fun describe(uuid: String): String? = cache[PlayerRef.parseOrNull(uuid)!!]?.let { "${it.name}|${it.language}|${it.vanished}" }

    fun find(name: String): String? = cache.find(name)?.player?.toString()

    /** Which class loader its copy of the core and of Kotlin came from. */
    fun loaders(): String = "${PlayerCache::class.java.classLoader}|${KotlinVersion::class.java.classLoader}"

    private object Inline : Scheduler {
        override fun run(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
            val task = object : Task {
                override val isCancelled: Boolean get() = false
                override fun cancel() {}
            }
            action(task)
            return task
        }

        override fun owns(where: Where): Boolean = true
    }

    private object Quiet : Log {
        override fun info(message: String) {}
        override fun warn(message: String, cause: Throwable?) {}
        override fun error(message: String, cause: Throwable?) {}
    }
}
