package co.xenastudios.xrewind

import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.db.Sql
import co.xenastudios.core.common.db.Statement
import co.xenastudios.xrewind.config.Legacy
import co.xenastudios.xrewind.store.Tables

/**
 * The functions xRewind hands the core as it starts (the first tables, the config's 1.x
 * migration): one class for them all rather than a lambda each, each lambda being a class of its
 * own in metaspace (decisions/2026-11-25-a-lighter-core.md).
 */
internal class Startup private constructor(private val kind: Int) : (Any?) -> Any? {
    override fun invoke(value: Any?): Any? = when (kind) {
        TABLES -> Tables.statements(value as Sql)
        else -> Legacy.toTwo(value as MigrationScope)
    }

    @Suppress("UNCHECKED_CAST")
    internal companion object {
        private const val TABLES = 0
        private const val CONFIG_ONE = 1

        val tables: (Sql) -> List<Statement> get() = Startup(TABLES) as (Sql) -> List<Statement>
        val configOne: MigrationScope.() -> Unit get() = Startup(CONFIG_ONE) as MigrationScope.() -> Unit
    }
}
