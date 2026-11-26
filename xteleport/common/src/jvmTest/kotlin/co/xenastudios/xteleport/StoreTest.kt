package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.Warp
import co.xenastudios.xteleport.store.Store
import co.xenastudios.xteleport.store.Tables
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store on a real SQLite database, and EssentialsX's files read and imported into it. */
class StoreTest {
    private val folder = Files.createTempDirectory("xteleport-store")
    private val database = (JdbcDatabase.open(DatabaseSettings(DatabaseSettings.Type.SQLITE, "xteleport_"), folder.resolve("xteleport.db"), Drivers.serverOnly(javaClass.classLoader), RecordingLog(), "Test") as Outcome.Ok).value
    private val store = Store(database)
    private val steve = PlayerRef.parseOrNull("8667ba71-b85a-4004-af54-457a9734eed7")!!
    private val world = WorldRef.of("world")

    init {
        database.migrate(Tables.MIGRATIONS).await()
    }

    @AfterTest
    fun clean() {
        database.close().await()
        folder.toFile().deleteRecursively()
    }

    private fun <T> CompletableFuture<T>.await(): T = get(15, TimeUnit.SECONDS)

    private fun <T, E> CompletableFuture<Outcome<T, E>>.ok(): T = (await() as Outcome.Ok).value

    @Test
    fun homesWarpsAndTheSpawnRoundTrip() {
        store.saveHome(Home(steve, "Base", Location(world, 1.5, 64.0, -2.5, 90f, 5f)), 10).ok()
        store.saveHome(Home(steve, "base", Location(world, 3.5, 64.0, -2.5)), 11).ok()
        assertEquals(listOf("base"), store.loadPlayer(steve).ok().homes.map { it.name })
        assertEquals(3.5, store.loadPlayer(steve).ok().homes.single().location.x)
        store.saveWarp(Warp("Shop", Location(world, 9.0, 65.0, 9.0)), 12).ok()
        store.saveSpawn(Location(world, 0.5, 70.0, 0.5), 13).ok()
        val server = store.loadServer().ok()
        assertEquals(listOf("Shop"), server.warps.map { it.name })
        assertEquals(70.0, server.spawn!!.location.y)
        store.deleteWarp("SHOP").ok()
        assertEquals(emptyList(), store.loadServer().ok().warps)
        store.setTpaOff(steve, true).ok()
        assertTrue(store.loadPlayer(steve).ok().tpaOff)
        assertEquals(1, store.forget(steve).ok())
        val forgotten = store.loadPlayer(steve).ok()
        assertEquals(emptyList(), forgotten.homes)
        assertEquals(false, forgotten.tpaOff)
    }

    @Test
    fun essentialsHomesWarpsAndSpawnAreImportedOnceAndKeepWhatsHere() {
        val essentials = folder.resolve("Essentials")
        fun write(path: String, text: String) {
            val file = essentials.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, text.trimIndent().toByteArray(StandardCharsets.UTF_8))
        }
        val worldId = "5b3b8f1c-3f2e-4e2a-9c39-6f2a1e7a0b11"
        write("userdata/$steve.yml", """
            last-account-name: Steve
            homes:
              base:
                world: $worldId
                world-name: world
                x: 10.5
                y: 64.0
                z: -3.5
                yaw: 90.0
                pitch: 0.0
              "my farm!":
                world: $worldId
                x: 1.0
                y: 70.0
                z: 1.0
                yaw: 0.0
                pitch: 0.0
              lost:
                world: 00000000-0000-0000-0000-000000000000
                x: 1.0
                y: 70.0
                z: 1.0
        """)
        write("userdata/not-a-player.yml", "homes: {}")
        write("warps/shop.yml", """
            world: world
            x: 5.0
            y: 65.0
            z: 5.0
            yaw: 0.0
            pitch: 0.0
            name: Shop
        """)
        write("spawn.yml", """
            spawns:
              default:
                world: world
                x: 0.5
                y: 66.0
                z: 0.5
                yaw: 0.0
                pitch: 0.0
        """)
        val found = EssentialsFiles(essentials).read { if (it == worldId || it == "world") "world" else null }
        assertEquals(listOf("base", "my_farm_"), found.homes.map { it.name })
        assertEquals(1, found.players)
        assertEquals(listOf("Shop"), found.warps.map { it.name })
        assertEquals(66.0, found.spawn!!.y)
        assertEquals(1, found.problems.size, found.problems.toString())
        // Something already here is kept.
        store.saveHome(Home(steve, "base", Location(world, 99.0, 64.0, 99.0)), 1).ok()
        val first = store.importAll(found.homes, found.warps, found.spawn, 2).ok()
        assertEquals(1, first.homes)
        assertEquals(1, first.warps)
        assertTrue(first.spawn)
        assertEquals(1, first.skipped)
        assertEquals(99.0, store.loadPlayer(steve).ok().homes.first { it.name == "base" }.location.x)
        val again = store.importAll(found.homes, found.warps, found.spawn, 3).ok()
        assertEquals(0, again.homes + again.warps)
        assertEquals(false, again.spawn)
    }

    @Test
    fun noEssentialsFolderFindsNothing() {
        val found = EssentialsFiles(folder.resolve("nowhere")).read { it }
        assertEquals(emptyList(), found.homes)
        assertNull(found.spawn)
    }
}
