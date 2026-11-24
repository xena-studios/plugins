package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.db.Migrations
import co.xenastudios.core.loader.Library
import co.xenastudios.core.loader.LibraryCache
import co.xenastudios.core.loader.OnDemandLibraries
import co.xenastudios.core.loader.Sha256
import co.xenastudios.core.testing.RecordingLog
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Drivers fetched by the loader when the server has none, and their pins. */
class OnDemandDriverTest {

    @TempDir
    lateinit var dir: Path

    private val pins: Map<String, String> = Paths.get(System.getProperty("xena.libraryPins")).readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .associate { it.substringBefore(' ') to it.substringAfter(' ').trim() }

    /** The jar a class on the test class path came from: Maven Central's, through Gradle. */
    private fun jarOf(className: String): Path = Paths.get(Class.forName(className).protectionDomain.codeSource.location.toURI())

    @Test
    fun everyDriverIsPinnedAtTheVersionTheTestsRun() {
        for (driver in DriverLibrary.entries) {
            val pin = pins[driver.coordinates]
            assertTrue(pin != null, "${driver.coordinates} has no pin in gradle/library-pins.txt")
            // The tests' driver is the jar the pin is for, so the pin, DriverLibrary and the
            // version catalog can't drift apart.
            assertEquals(pin, Sha256.of(jarOf(driver.driverClass)), driver.coordinates)
        }
    }

    @Test
    fun aServerWithoutSqliteGetsTheFetchedOne() {
        val jar = jarOf(DriverLibrary.SQLITE.driverClass)
        val (group, artifact, version) = DriverLibrary.SQLITE.coordinates.split(':')
        val library = Library(group, artifact, version, Sha256.of(jar), DriverLibrary.SQLITE.driverClass)
        // The family's cache already has it, as when another plugin fetched it: no mirror needed.
        val cache = LibraryCache(dir.resolve("libraries"), emptyList(), "Set a mirror.")
        Files.createDirectories(cache.location(library).parent)
        Files.copy(jar, cache.location(library))

        // A server class loader that sees no driver at all.
        val bare = URLClassLoader(arrayOf(), null)
        val fetcher = OnDemandDriverFetcher(OnDemandLibraries(listOf(library), cache))
        val database = JdbcDatabase.open(DatabaseSettings(prefix = "t_"), dir.resolve("data.db"), Drivers(bare, fetcher), RecordingLog(), "Test").ok()
        assertEquals(2, database.session { Migrations.run(it, database.sql, MIGRATIONS) }.await().ok())
        database.close().await()

        // Without the fetcher, the same server is told there's no driver.
        val none = JdbcDatabase.open(DatabaseSettings(prefix = "t_"), dir.resolve("other.db"), Drivers.serverOnly(bare), RecordingLog(), "Test")
        assertEquals(DbError.Kind.NO_DRIVER, none.kind())
    }

    @Test
    fun aDriverTheBuildDidNotPinIsNoDriver() {
        val fetcher = OnDemandDriverFetcher(OnDemandLibraries(emptyList(), LibraryCache(dir, emptyList(), "")))
        val result = fetcher.fetch(DriverLibrary.MYSQL)
        assertTrue(result is Outcome.Err && result.error.kind == DbError.Kind.NO_DRIVER && "pins no com.mysql:mysql-connector-j" in result.error.detail, result.toString())
    }
}
