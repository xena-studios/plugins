package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.loader.LibraryException
import co.xenastudios.core.loader.OnDemandLibraries
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.Driver

/**
 * A JDBC driver the core can use when the server has none, or an old one: what to download, and
 * the class to load from it. The versions are the ones pinned in gradle/library-pins.txt
 * (`DriverPinsTest` keeps them the same). No driver is shaded into a plugin (DECISIONS.md, "A shared database
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

    // Another plugin's local database, read once by an import ([IsolatedDriver]), never the
    // plugin's own. H2's file formats don't mix: each version reads its own format only, named in
    // the file's first line (`format:1`, 2 or 3), so there's one driver per format.

    /** H2 1.4.200, for H2 files of format 1 (`.mv.db` written by H2 1.4.177 to 1.4.200, and 1.4's `.h2.db`). */
    H2_FORMAT_1("com.h2database:h2:1.4.200", "org.h2.Driver"),

    /** H2 2.1.214, for format 2 (written by H2 2.0.202 to 2.1.214). */
    H2_FORMAT_2("com.h2database:h2:2.1.214", "org.h2.Driver"),

    /** H2 2.4.240, for format 3 (written by H2 2.2.220 and later). Java 11 or newer. */
    H2_FORMAT_3("com.h2database:h2:2.4.240", "org.h2.Driver"),

    /** HSQLDB 2.5.2, the newest whose jar runs on Java 8, for files written by HSQLDB 2.x. */
    HSQLDB("org.hsqldb:hsqldb:2.5.2", "org.hsqldb.jdbc.JDBCDriver"),
}

/**
 * Downloads a [DriverLibrary] and returns where its jar is, checked against the hash pinned at
 * build time. Each platform's adapter passes an [OnDemandDriverFetcher], built on the core's
 * library loader (`core-loader`), whose cache in `plugins/Xena/libraries/` keeps one copy for
 * the family; [Drivers.serverOnly] has none, and a server without a driver is told which one
 * to install.
 */
public fun interface DriverFetcher {
    public fun fetch(library: DriverLibrary): Outcome<Path, DbError>
}

/**
 * A [DriverFetcher] on the plugin's on-demand libraries: the drivers its build pinned
 * (`onDemand` in the xena.fetched-libraries convention, hashes in gradle/library-pins.txt),
 * downloaded into the family's shared cache the first time a server without one opens a
 * database, and checked against the pin every time. The version [DriverLibrary.coordinates]
 * names if the build pins it (H2 is pinned at several), the one pinned otherwise, so a bump
 * needs only the build changed.
 */
public class OnDemandDriverFetcher(private val libraries: OnDemandLibraries) : DriverFetcher {
    override fun fetch(library: DriverLibrary): Outcome<Path, DbError> {
        val (group, artifact, version) = library.coordinates.split(':')
        return try {
            Outcome.Ok(libraries.fetch(group, artifact, version))
        } catch (e: LibraryException) {
            Outcome.Err(DbError(DbError.Kind.NO_DRIVER, e.message ?: "couldn't download ${library.coordinates}"))
        }
    }
}

/**
 * Where JDBC drivers come from, tried in order: the server's own, seen through [loader] (Paper's
 * bundled ones, Spigot's old ones), then a fetched jar, loaded in a class loader of its own.
 * Drivers are used directly, never through `DriverManager`, which only hands out drivers the
 * caller's class loader can see.
 */
public class Drivers(
    private val loader: ClassLoader,
    /** Where fetched drivers come from; `null` with none ([serverOnly]). */
    public val fetcher: DriverFetcher? = null,
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
