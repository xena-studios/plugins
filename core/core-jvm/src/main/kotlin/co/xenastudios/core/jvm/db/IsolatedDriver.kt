package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.loader.DriverRelease
import java.net.URL
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/**
 * A fetched JDBC driver in a class loader of its own, for one job and then let go: another
 * plugin's database file read by an import, with the driver version that file needs. Nothing of
 * it is loaded until [open], and [close] takes it out of `DriverManager` (where H2 and HSQLDB put
 * themselves) and closes its class loader, so once its connections are closed nothing holds it.
 *
 * Its parent is the platform class loader: it has `java.sql` and none of the server's classes,
 * so a plugin's own shaded H2 or HSQLDB can't be the one loaded.
 */
public class IsolatedDriver private constructor(
    private val driver: Driver,
    private val loader: Loader,
    /** What was loaded, `group:artifact:version`, for messages. */
    public val coordinates: String,
) : AutoCloseable {

    /** Connects with [url] and [properties] (`user`, `password`); throws as the driver does. */
    @Throws(SQLException::class)
    public fun connect(url: String, properties: Properties): Connection =
        driver.connect(url, properties) ?: throw SQLException("$coordinates doesn't take $url")

    /** Takes the driver out of `DriverManager` and closes its class loader. Close its connections first. */
    override fun close() {
        try {
            // DriverRelease's own bytes, defined in the driver's loader, so DriverManager sees a
            // caller that can see the driver (see DriverRelease).
            val name = DriverRelease::class.java.name
            val bytes = DriverRelease::class.java.getResourceAsStream("/" + name.replace('.', '/') + ".class")?.use { it.readBytes() }
            if (bytes != null) (loader.define(name, bytes).getDeclaredConstructor().newInstance() as Runnable).run()
        } catch (e: Exception) {
            // Still registered, so held until the server stops: a leak, not a failure.
        } catch (e: LinkageError) {
        } finally {
            loader.close()
        }
    }

    private class Loader(url: URL) : URLClassLoader(arrayOf(url), ClassLoader.getSystemClassLoader().parent) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    public companion object {
        /**
         * Fetches [library] with [fetcher] (from the family's cache, or downloaded) and loads its
         * driver. The error says why not, as the loader does: offline, which jar to put where.
         */
        @JvmStatic
        public fun open(fetcher: DriverFetcher?, library: DriverLibrary): Outcome<IsolatedDriver, DbError> {
            fetcher ?: return Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "this platform can't download ${library.coordinates}"))
            val jar = when (val fetched = fetcher.fetch(library)) {
                is Outcome.Ok -> fetched.value
                is Outcome.Err -> return fetched
            }
            val loader = Loader(jar.toUri().toURL())
            return try {
                val driver = Class.forName(library.driverClass, true, loader).getDeclaredConstructor().newInstance() as Driver
                Outcome.Ok(IsolatedDriver(driver, loader, library.coordinates))
            } catch (e: UnsupportedClassVersionError) {
                loader.close()
                Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "${library.coordinates} needs a newer Java than this server's ${System.getProperty("java.version")}"))
            } catch (e: Exception) {
                loader.close()
                Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "couldn't load ${library.driverClass} from $jar: $e"))
            } catch (e: LinkageError) {
                loader.close()
                Outcome.Err(DbError(DbError.Kind.NO_DRIVER, "couldn't load ${library.driverClass} from $jar: $e"))
            }
        }
    }
}
