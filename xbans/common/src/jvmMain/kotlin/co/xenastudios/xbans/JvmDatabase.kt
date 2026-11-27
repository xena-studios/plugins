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
    // Where the config says the data is: the settings, the SQLite file, and why it isn't what was
    // asked for. The stdlib's Triple, not a class of xBans' own made as the server starts.
    private fun where(config: Config): Triple<DatabaseSettings, Path, String?> {
        val plan = family.storage(config, XBansConfig.storage)
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve(FILE)
        return Triple(plan.database, file, plan.problem)
    }

    override fun describe(config: Config): String {
        val (settings, file) = where(config)
        return listOf(settings, settings.mysql.password.hashCode(), file, config[XBansConfig.network.enabled], config[XBansConfig.network.checkEvery], config[XBansConfig.ips.hash]).joinToString("|")
    }

    override fun open(config: Config): Outcome<Database, String> {
        val (settings, file, problem) = where(config)
        problem?.let(log::warn)
        return when (val opened: Outcome<Database, DbError> = JdbcDatabase.open(settings, file, drivers, log, XBansPlugin.NAME)) {
            is Outcome.Ok -> Outcome.Ok(opened.value)
            is Outcome.Err -> Outcome.Err(
                "Couldn't open ${if (settings.type == DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${file.fileName}"}: ${opened.error.detail}.",
            )
        }
    }

    public companion object {
        /** The SQLite file, in the plugin's folder: 1.x's. */
        public const val FILE: String = "punishments.db"
    }
}
