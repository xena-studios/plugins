package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.Driver

/**
 * A JDBC driver the core can use when the server has none, or an old one: what to download, and
 * the class to load from it. No driver is shaded into a plugin (DECISIONS.md, "A shared database
 * layer, with no drivers shaded in"): Paper and Folia ship both, Spigot ships old ones, and the
 * rest are fetched once for the family.
 */
public enum class DriverLibrary(
    /** `group:artifact:version` on Maven Central. */
    public val coordinates: String,
    /** The `java.sql.Driver` in it. */
    public val driverClass: String,
) {
    /** SQLite, for a server without it (Fabric, NeoForge, Sponge) or with one older than [co.xenastudios.core.common.db.Dialect.Sqlite.MINIMUM]. */
    SQLITE("org.xerial:sqlite-jdbc:3.49.1.0", "org.sqlite.JDBC"),

    /** MySQL's, which talks to MariaDB too, for `storage: mysql` on a server without it. */
    MYSQL("com.mysql:mysql-connector-j:9.2.0", "com.mysql.cj.jdbc.Driver"),
}

/**
 * Downloads a [DriverLibrary] and returns where its jar is, checked against the hash pinned at
 * build time: the seam for the core's library loader (`core-loader`, #143), whose cache in
 * `plugins/Xena/libraries/` does exactly this for the Kotlin stdlib. Each platform's adapter
 * passes one built on it; until then [Drivers.serverOnly] has none, and a server without a driver
 * is told which one to install.
 */
public fun interface DriverFetcher {
    public fun fetch(library: DriverLibrary): Outcome<Path, DbError>
}

/**
 * Where JDBC drivers come from, tried in order: the server's own, seen through [loader] (Paper's
 * bundled ones, Spigot's old ones), then a fetched jar, loaded in a class loader of its own.
 * Drivers are used directly, never through `DriverManager`, which only hands out drivers the
 * caller's class loader can see.
 */
public class Drivers(
    private val loader: ClassLoader,
    private val fetcher: DriverFetcher? = null,
) {
    /** One driver to try, and where it came from, for the console. */
    internal class Candidate(val driver: Driver, val source: String)

    /** SQLite's drivers to try: the server's, then a fetched one. */
    internal fun sqlite(): Sequence<Outcome<Candidate, DbError>> = sequence {
        server(DriverLibrary.SQLITE.driverClass)?.let { yield(Outcome.Ok(it)) }
        fetched(DriverLibrary.SQLITE)?.let { yield(it) }
    }

    /** MySQL's drivers to try: the server's (Connector/J 8+, then 5.1 on old servers), then a fetched one. */
    internal fun mysql(): Sequence<Outcome<Candidate, DbError>> = sequence {
        (server(DriverLibrary.MYSQL.driverClass) ?: server("com.mysql.jdbc.Driver"))?.let { yield(Outcome.Ok(it)) }
        fetched(DriverLibrary.MYSQL)?.let { yield(it) }
    }

    private fun server(name: String): Candidate? = try {
        Candidate(Class.forName(name, true, loader).getDeclaredConstructor().newInstance() as Driver, "the server's")
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: LinkageError) {
        null
    } catch (e: ClassCastException) {
        null
    }

    private fun fetched(library: DriverLibrary): Outcome<Candidate, DbError>? {
        val fetcher = fetcher ?: return null
        val jar = when (val fetched = fetcher.fetch(library)) {
            is Outcome.Ok -> fetched.value
            is Outcome.Err -> return fetched
        }
        return try {
            // The platform's class loader as the parent: it has java.sql, and none of the
            // server's classes, so this jar's driver is the one loaded.
            val isolated = URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getSystemClassLoader().parent)
            val driver = Class.forName(library.driverClass, true, isolated).getDeclaredConstructor().newInstance() as Driver
            Outcome.Ok(Candidate(driver, "downloaded ${library.coordinates}"))
        } catch (e: Exception) {
            Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "couldn't load ${library.driverClass} from $jar: $e"))
        } catch (e: LinkageError) {
            Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "couldn't load ${library.driverClass} from $jar: $e"))
        }
    }

    public companion object {
        /** Only what the server has: no downloads. */
        @JvmStatic
        public fun serverOnly(loader: ClassLoader): Drivers = Drivers(loader)
    }
}
