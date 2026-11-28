package co.xenastudios.xbackup

import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.backup.Databases
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.RegionLayout
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Session
import co.xenastudios.xbackup.store.Throttle
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Random
import java.util.zip.DeflaterOutputStream

/** A small server folder to back up: a world with region files, plugins with files and a database. */
object Fixtures {
    /** A region file of [chunks] chunks (the first ones), each a zlib record of random-ish bytes. */
    fun region(file: Path, chunks: Int, seed: Long, timestamp: Int = 1_700_000_000) {
        val random = Random(seed)
        val locations = IntArray(RegionLayout.CHUNKS)
        val records = ArrayList<Pair<Int, ByteArray>>()
        var sector = 2
        for (i in 0 until chunks) {
            val payload = ByteArray(2000 + random.nextInt(9000)).also { random.nextBytes(it); for (j in it.indices step 3) it[j] = 0 }
            val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(payload) } }.toByteArray()
            val record = ByteBuffer.allocate(5 + compressed.size).putInt(compressed.size + 1).put(2).put(compressed).array()
            val sectors = (record.size + RegionLayout.SECTOR - 1) / RegionLayout.SECTOR
            locations[i] = (sector shl 8) or sectors
            records += sector to record
            sector += sectors
        }
        val out = ByteBuffer.allocate(sector * RegionLayout.SECTOR)
        for (i in 0 until RegionLayout.CHUNKS) out.putInt(locations[i])
        for (i in 0 until RegionLayout.CHUNKS) out.putInt(if (locations[i] != 0) timestamp else 0)
        for ((at, record) in records) {
            out.position(at * RegionLayout.SECTOR)
            out.put(record)
        }
        Files.createDirectories(file.parent)
        Files.write(file, out.array())
    }

    /** Rewrites one chunk of [file] in place with new bytes, moving it to the end and stamping it [timestamp]. */
    fun touchChunk(file: Path, chunk: Int, seed: Long, timestamp: Int) {
        val bytes = Files.readAllBytes(file)
        val random = Random(seed)
        val payload = ByteArray(3000).also(random::nextBytes)
        val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(payload) } }.toByteArray()
        val record = ByteBuffer.allocate(5 + compressed.size).putInt(compressed.size + 1).put(2).put(compressed).array()
        val sectors = (record.size + RegionLayout.SECTOR - 1) / RegionLayout.SECTOR
        val at = bytes.size / RegionLayout.SECTOR
        val grown = ByteBuffer.allocate((at + sectors) * RegionLayout.SECTOR).put(bytes)
        grown.position(at * RegionLayout.SECTOR)
        grown.put(record)
        grown.putInt(chunk * 4, (at shl 8) or sectors)
        grown.putInt(RegionLayout.SECTOR + chunk * 4, timestamp)
        Files.write(file, grown.array())
    }

    /** A region file's bytes as a restore writes it: the header and every chunk's record, the sectors nothing points at zeros. */
    fun live(file: Path): ByteArray {
        val bytes = Files.readAllBytes(file)
        val out = ByteArray(bytes.size)
        System.arraycopy(bytes, 0, out, 0, RegionLayout.HEADER)
        val header = ByteBuffer.wrap(bytes)
        for (i in 0 until RegionLayout.CHUNKS) {
            val location = header.getInt(i * 4)
            if (location == 0) continue
            val start = RegionLayout.sectorOffset(location).toInt() * RegionLayout.SECTOR
            val length = header.getInt(start) + 4
            System.arraycopy(bytes, start, out, start, length)
        }
        return out
    }

    /** A server folder with a world, plugins and a SQLite database. */
    fun server(root: Path): Path {
        Files.createDirectories(root.resolve("world/region"))
        Files.write(root.resolve("world/level.dat"), "level".toByteArray())
        region(root.resolve("world/region/r.0.0.mca"), 40, 1)
        region(root.resolve("world/region/r.0.-1.mca"), 12, 2)
        Files.createDirectories(root.resolve("plugins/Some"))
        Files.write(root.resolve("plugins/Some/config.yml"), "a: 1\n".toByteArray())
        Files.write(root.resolve("plugins/Some/big.bin"), ByteArray(3 shl 20).also { Random(3).nextBytes(it) })
        Files.write(root.resolve("server.properties"), "motd=hi\n".toByteArray())
        sqlite(root.resolve("plugins/Some/data.db"), 50)
        return root
    }

    /** A SQLite database with [rows] rows in table `t`. */
    fun sqlite(file: Path, rows: Int) {
        Files.createDirectories(file.parent)
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS t (n INTEGER)") }
            c.prepareStatement("INSERT INTO t (n) VALUES (?)").use { s ->
                repeat(rows) {
                    s.setInt(1, it)
                    s.executeUpdate()
                }
            }
        }
    }

    fun rows(file: Path): Int = DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM t").use { it.next(); it.getInt(1) } }
    }

    fun repository(folder: Path): Repository = Repository.open(LocalBackend(folder), folder.resolve("tmp"), true)

    /** A plan for [root]: its world read during the pause, the rest after. */
    fun plan(root: Path, repository: Path, reason: String = "test", known: Set<String> = emptySet(), external: List<String> = emptyList()): Backup.Plan = Backup.Plan(
        root, listOf("world"), listOf("plugins", "server.properties"), emptyList(), setOf(repository, root.resolve("plugins/xBackup")), emptyMap(), reason, "test", root.resolve("plugins/xBackup/temp"),
        Throttle.NONE, Session.Options(6, 4L shl 20, 0), known, external,
    )

    val sqlite: Databases = Databases.Sqlite(org.sqlite.JDBC())

    /** Every file under [folder], relative, with its bytes. */
    fun files(folder: Path): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        Files.walk(folder).use { walk -> for (path in walk) if (Files.isRegularFile(path)) out[folder.relativize(path).toString().replace('\\', '/')] = Files.readAllBytes(path) }
        return out
    }
}
