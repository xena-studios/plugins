package co.xenastudios.xteleport

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xteleport.config.XTeleportConfig
import java.nio.file.Path

/**
 * Opens xTeleport's database where `storage` says, on the JVM: SQLite in the plugin's folder
 * (`xteleport.db`, 1.x's file) or the family's, or MySQL. Off the server's threads: it connects.
 */
public object JvmDatabase {
    /** The SQLite file, in the plugin's folder: 1.x's. */
    public const val FILE: String = "xteleport.db"

    /** Where [config] says the data is. */
    public class Where(public val settings: DatabaseSettings, public val sqliteFile: Path, public val problem: String?)

    /** Where the data is: [folder] is the plugin's, [familyFolder] the family's (`plugins/Xena/`). */
    @JvmStatic
    public fun where(config: Config, family: Family, folder: Path, familyFolder: Path): Where {
        val plan = family.storage(config, XTeleportConfig.storage)
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve(FILE)
        return Where(plan.database, file, plan.problem)
    }

    /** Opens what [where] names, as `open` wants it: the database, or why not, in English. */
    @JvmStatic
    public fun open(where: Where, drivers: Drivers, log: Log): Outcome<Database, String> {
        where.problem?.let(log::warn)
        return when (val opened: Outcome<Database, DbError> = JdbcDatabase.open(where.settings, where.sqliteFile, drivers, log, XTeleportPlugin.NAME)) {
            is Outcome.Ok -> Outcome.Ok(opened.value)
            is Outcome.Err -> Outcome.Err(
                "Couldn't open ${if (where.settings.type == DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${where.sqliteFile.fileName}"}: ${opened.error.detail}.",
            )
        }
    }
}
