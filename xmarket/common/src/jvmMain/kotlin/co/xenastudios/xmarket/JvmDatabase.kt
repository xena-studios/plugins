package co.xenastudios.xmarket

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xmarket.config.XMarketConfig
import java.nio.file.Path

/** Opens xMarket's database where `storage` says: SQLite (`xmarket.db`) in the plugin's folder or the family's, or MySQL. Off the server's threads. */
public object JvmDatabase {
    public const val FILE: String = "xmarket.db"

    public class Where(public val settings: DatabaseSettings, public val sqliteFile: Path, public val problem: String?)

    @JvmStatic
    public fun where(config: Config, family: Family, folder: Path, familyFolder: Path): Where {
        val plan = family.storage(config, XMarketConfig.storage)
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve(FILE)
        return Where(plan.database, file, plan.problem)
    }

    @JvmStatic
    public fun open(where: Where, drivers: Drivers, log: Log): Outcome<Database, String> {
        where.problem?.let(log::warn)
        return when (val opened: Outcome<Database, DbError> = JdbcDatabase.open(where.settings, where.sqliteFile, drivers, log, XMarketPlugin.NAME)) {
            is Outcome.Ok -> Outcome.Ok(opened.value)
            is Outcome.Err -> Outcome.Err(
                "Couldn't open ${if (where.settings.type == DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${where.sqliteFile.fileName}"}: ${opened.error.detail}.",
            )
        }
    }
}
