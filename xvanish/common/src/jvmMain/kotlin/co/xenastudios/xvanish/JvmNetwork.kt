package co.xenastudios.xvanish

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xvanish.config.XVanishConfig
import java.nio.file.Path

/**
 * Opens network mode's database where `storage` says, on the JVM: MySQL, or the family's shared
 * database. Only with `network.enabled`: a single server opens none. Off the server's threads: it connects.
 */
public object JvmNetwork {
    /** Whether [config] asks for network mode. */
    @JvmStatic
    public fun wanted(config: Config): Boolean = config[XVanishConfig.network.enabled]

    /** Opens the database, as [XVanishPlugin.openNetwork] wants it: [folder] is the plugin's, [familyFolder] the family's. */
    @JvmStatic
    public fun open(config: Config, family: Family, folder: Path, familyFolder: Path, drivers: Drivers, log: Log): Outcome<Database, String> {
        val plan = family.storage(config, XVanishConfig.storage)
        plan.problem?.let(log::warn)
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve("xvanish.db")
        return when (val opened = JdbcDatabase.open(plan.database, file, drivers, log, XVanishPlugin.NAME)) {
            is Outcome.Ok -> Outcome.Ok(opened.value)
            is Outcome.Err -> Outcome.Err(
                "Couldn't open ${if (plan.database.type == DatabaseSettings.Type.MYSQL) "MySQL" else "the SQLite database ${file.fileName}"} for network mode: ${opened.error.detail}.",
            )
        }
    }
}
