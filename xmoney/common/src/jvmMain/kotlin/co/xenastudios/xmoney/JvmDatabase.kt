package co.xenastudios.xmoney

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.xmoney.config.XMoneyConfig
import java.nio.file.Path

/**
 * Opens xMoney's database where `storage` says, on the JVM: SQLite in the plugin's folder
 * (`money.db`, 1.x's file) or the family's, or MySQL. Off the server's threads: it connects.
 */
public object JvmDatabase {
    /** The SQLite file, in the plugin's folder: 1.x's. */
    public const val FILE: String = "money.db"

    /** How long a network's connections wait to connect and for any one reply, whatever `storage.mysql.properties` says: a caller may be waiting on a payment. */
    public const val NETWORK_TIMEOUT_MS: Int = 3_000

    /** Where [config] says the data is, and whether network mode is on for it (MySQL, `network.enabled`). */
    public class Where(public val settings: DatabaseSettings, public val sqliteFile: Path, public val network: Boolean, public val problem: String?)

    /** Where the data is: [folder] is the plugin's, [familyFolder] the family's (`plugins/Xena/`). */
    @JvmStatic
    public fun where(config: Config, family: Family, folder: Path, familyFolder: Path): Where {
        val plan = family.storage(config, XMoneyConfig.storage)
        var settings = plan.database
        val network = config[XMoneyConfig.network.enabled] && settings.type == DatabaseSettings.Type.MYSQL
        if (network) {
            // Last, so they win over the owner's: Connector/J takes a repeated property's last value.
            val bounded = "connectTimeout=$NETWORK_TIMEOUT_MS&socketTimeout=$NETWORK_TIMEOUT_MS"
            val own = settings.mysql.properties.trim()
            settings = settings.copy(mysql = settings.mysql.copy(properties = if (own.isEmpty()) bounded else "$own&$bounded"))
        }
        val file = if (plan.inFamilyFolder) familyFolder.resolve(Family.DATABASE_FILE.toString()) else folder.resolve(FILE)
        return Where(settings, file, network, plan.problem)
    }

    /** Opens what [where] names. */
    @JvmStatic
    public fun open(where: Where, drivers: Drivers, log: Log): Outcome<Database, DbError> {
        where.problem?.let(log::warn)
        return JdbcDatabase.open(where.settings, where.sqliteFile, drivers, log, XMoneyPlugin.NAME)
    }
}
