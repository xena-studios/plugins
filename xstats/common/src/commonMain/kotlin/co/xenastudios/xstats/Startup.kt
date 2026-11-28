package co.xenastudios.xstats

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.db.Sql
import co.xenastudios.xstats.config.Legacy
import co.xenastudios.xstats.store.Tables

/**
 * The functions xStats hands the core as it starts (the tables' two steps, the config's 1.x
 * migration): one class for them all rather than a lambda each, each lambda being a class of its own
 * in metaspace (decisions/2026-11-25-a-lighter-core.md).
 */
internal class Startup(private val kind: Int) : (Any?) -> Any? {
    override fun invoke(value: Any?): Any? = when (kind) {
        STARTUP_TABLES -> Tables.first(value as Sql)
        STARTUP_SOURCES -> Tables.second(value as Sql)
        else -> Legacy.toTwo(value as MigrationScope)
    }
}

// Its kinds: constants, compiled into the callers, rather than a companion (one more class at startup).
internal const val STARTUP_TABLES: Int = 0
internal const val STARTUP_SOURCES: Int = 1
internal const val STARTUP_CONFIG_ONE: Int = 2
