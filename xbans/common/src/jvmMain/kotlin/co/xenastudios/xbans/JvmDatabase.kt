package co.xenastudios.xbans

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xbans.config.XBansConfig
import java.nio.file.Path

/**
 * Opens xBans' database where `storage` says, on the JVM: SQLite in the plugin's folder
 * (`punishments.db`, 1.x's file) or the family's, or MySQL. Off the server's threads: it connects.
 */
public class JvmDatabase(
    private val family: Family,
    /** The plugin's folder. */
    private val folder: Path,
    /** The family's, `plugins/Xena/` (`config/xena/` on Fabric). */
    private val familyFolder: Path,
    private val drivers: Drivers,
    private val log: Log,
) : Opener {
    private class Where(val settings: DatabaseSettings, val sqliteFile: Path, val problem: String?)

    private fun where(config: Config): Where {
        val plan = family.storage(config, XBansConfig.storage)
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve(FILE)
        return Where(plan.database, file, plan.problem)
    }

    override fun describe(config: Config): String {
        val where = where(config)
        return listOf(where.settings, where.settings.mysql.password.hashCode(), where.sqliteFile, XBansConfig.network.read(config), config[XBansConfig.ips.hash]).joinToString("|")
    }

    override fun open(config: Config): Outcome<Database, String> {
        val where = where(config)
        where.problem?.let(log::warn)
        return when (val opened: Outcome<Database, DbError> = JdbcDatabase.open(where.settings, where.sqliteFile, drivers, log, XBansPlugin.NAME)) {
            is Outcome.Ok -> Outcome.Ok(opened.value)
            is Outcome.Err -> Outcome.Err(
                "Couldn't open ${if (where.settings.type == DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${where.sqliteFile.fileName}"}: ${opened.error.detail}.",
            )
        }
    }

    public companion object {
        /** The SQLite file, in the plugin's folder: 1.x's. */
        public const val FILE: String = "punishments.db"
    }
}
