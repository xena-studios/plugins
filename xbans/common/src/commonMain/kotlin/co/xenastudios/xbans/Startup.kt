package co.xenastudios.xbans

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.MigrationScope
import co.xenastudios.core.common.db.Sql
import co.xenastudios.xbans.config.Legacy
import co.xenastudios.xbans.model.Length
import co.xenastudios.xbans.store.Tables

/**
 * The functions xBans hands the core as it starts (a length's config type, the first tables, the
 * config's 1.x migration): one class for them all rather than a lambda each, each lambda being a
 * class of its own in metaspace (decisions/2026-11-25-a-lighter-core.md).
 */
internal class Startup private constructor(private val kind: Int) : (Any?) -> Any? {
    override fun invoke(value: Any?): Any? = when (kind) {
        WRITE_LENGTH -> (value as Length).typed()
        READ_LENGTH -> Length.typeRead(value as String)
        TABLES -> Tables.statements(value as Sql)
        else -> Legacy.toTwo(value as MigrationScope)
    }

    @Suppress("UNCHECKED_CAST")
    internal companion object {
        private const val WRITE_LENGTH = 0
        private const val READ_LENGTH = 1
        private const val TABLES = 2
        private const val CONFIG_ONE = 3

        val writeLength: (Length) -> String get() = Startup(WRITE_LENGTH) as (Length) -> String
        val readLength: (String) -> Outcome<Length, String> get() = Startup(READ_LENGTH) as (String) -> Outcome<Length, String>
        val tables: (Sql) -> List<co.xenastudios.core.common.db.Statement> get() = Startup(TABLES) as (Sql) -> List<co.xenastudios.core.common.db.Statement>
        val configOne: MigrationScope.() -> Unit get() = Startup(CONFIG_ONE) as MigrationScope.() -> Unit
    }
}
