package co.xenastudios.xbackup

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.db.Migration
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.db.migrate
import co.xenastudios.core.jvm.db.Drivers
import co.xenastudios.core.jvm.db.JdbcDatabase
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.backup.Databases
import co.xenastudios.xbackup.restore.Restore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The family test (ROADMAP.md, The family: "every plugin registers its data and pauses its
 * database for a backup"): xMoney's and xBans' databases, with their own tables, opened the way
 * the plugins open them, are copied by their own writers during a backup, while they're written,
 * with no SQLite copier of xBackup's own; and the copies restore whole.
 */
class FamilyTest {
    private val folder: Path = Files.createTempDirectory("xbackup-family")
    private val root: Path = Fixtures.server(folder.resolve("server"))
    private val repo: Path = folder.resolve("repository")
    private val board = JvmFamilyBoard(JvmFamilyBoard.newRoot())
    private val open = ArrayList<Database>()
    private val drivers = Drivers.serverOnly(FamilyTest::class.java.classLoader)

    @AfterTest
    fun clean() {
        open.forEach { it.close().get(10, TimeUnit.SECONDS) }
        JvmFamilyBoard.server = null
        folder.toFile().deleteRecursively()
    }

    private fun database(name: String, file: Path, prefix: String, migrations: List<Migration>): Database {
        JvmFamilyBoard.server = board
        val database = (JdbcDatabase.open(DatabaseSettings(prefix = prefix), file, drivers, RecordingLog(), name) as Outcome.Ok).value
        open += database
        assertTrue(database.migrate(migrations).get(10, TimeUnit.SECONDS) is Outcome.Ok)
        return database
    }

    private fun backup(): Backup.Result {
        val known = Databases.Family.sqliteFiles(board).keys
        return Fixtures.repository(repo).use { repository ->
            repository.lock(true).use {
                // No copier of xBackup's own: every Xena database must come from its plugin's writer.
                Backup.run(repository, Fixtures.plan(root, repo, known = known), Backup.Pause.None, Databases.Family(board, null), { false }) {}
            }
        }
    }

    private fun count(file: Path, table: String): Int = java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }
    }

    @Test
    fun xMoneyAndXBansDataComeThroughTheirOwnWriters() {
        val money = database("xMoney", root.resolve("plugins/xMoney/money.db"), "xmoney_", co.xenastudios.xmoney.ledger.Tables.MIGRATIONS)
        val bans = database("xBans", root.resolve("plugins/xBans/punishments.db"), "xbans_", co.xenastudios.xbans.store.Tables.MIGRATIONS)
        assertEquals(listOf("xBans", "xMoney"), Databases.Family.sqliteFiles(board).values.sorted())
        bans.write(
            listOf(bans.sql.raw(
                "INSERT INTO {p}punishments (type, uuid, name, ip_based, reason, staff_name, staff_weight, created, expires, scope, origin, silent) VALUES (1, ?, 'Steve', 0, 'Hacking', 'Console', 2147483647, 1, -1, 'global', 'server', 0)",
                SqlValue.Text("8667ba71-b85a-4004-af54-457a9734eed7"),
            )),
        ).get(10, TimeUnit.SECONDS)
        // xMoney keeps paying while the backup runs.
        val paid = AtomicInteger()
        val stop = AtomicBoolean()
        val payer = Thread {
            var n = 0L
            while (!stop.get()) {
                money.write(
                    listOf(money.sql.raw(
                        "INSERT INTO {p}transactions (time, uuid, currency, amount, balance, kind, source, reason) VALUES (?, ?, 'default', 100, ?, 'deposit', 'test', '')",
                        SqlValue.Integer(n), SqlValue.Text("8667ba71-b85a-4004-af54-457a9734eed7"), SqlValue.Integer(n * 100),
                    )),
                ).get(10, TimeUnit.SECONDS)
                n++
                paid.incrementAndGet()
            }
        }
        payer.start()
        val result = try {
            while (paid.get() < 100) Thread.sleep(5)
            backup()
        } finally {
            stop.set(true)
            payer.join(10_000)
        }
        // Only the fixture's database no plugin of the family has, which nothing here can copy.
        assertEquals(listOf(LEFT_OUT), result.snapshot.warnings)
        val out = folder.resolve("out")
        Fixtures.repository(repo).use { repository ->
            val snapshot = repository.snapshots(ArrayList()).single()
            Restore.write(repository, Restore.expand(repository, snapshot, listOf("plugins"), emptySet()), out)
        }
        assertEquals(1, count(out.resolve("plugins/xBans/punishments.db"), "xbans_punishments"))
        val restored = count(out.resolve("plugins/xMoney/money.db"), "xmoney_transactions")
        assertTrue(restored in 100..paid.get(), "$restored payments, of ${paid.get()} made")
        // A copy, not the raw file: no write-ahead log came with it, and it's whole.
        assertTrue(!Files.exists(out.resolve("plugins/xMoney/money.db-wal")))
    }

    @Test
    fun theFamilysSharedFileIsCopiedOnce() {
        val shared = root.resolve("plugins/Xena/xena.db")
        val money = database("xMoney", shared, "xmoney_", co.xenastudios.xmoney.ledger.Tables.MIGRATIONS)
        database("xBans", shared, "xbans_", co.xenastudios.xbans.store.Tables.MIGRATIONS)
        money.write(listOf(money.sql.raw("INSERT INTO {p}accounts (uuid, name, player, created) VALUES ('8667ba71-b85a-4004-af54-457a9734eed7', 'Steve', 1, 1)"))).get(10, TimeUnit.SECONDS)
        val result = backup()
        assertEquals(listOf(LEFT_OUT), result.snapshot.warnings)
        val out = folder.resolve("out")
        Fixtures.repository(repo).use { repository ->
            val snapshot = repository.snapshots(ArrayList()).single()
            Restore.write(repository, Restore.expand(repository, snapshot, listOf("plugins/Xena"), emptySet()), out)
        }
        assertEquals(1, count(out.resolve("plugins/Xena/xena.db"), "xmoney_accounts"))
        assertEquals(0, count(out.resolve("plugins/Xena/xena.db"), "xbans_punishments"))
    }

    @Test
    fun mysqlIsRecordedAsOutsideTheBackup() {
        val mysql = arrayOfNulls<Any>(co.xenastudios.core.common.family.FamilyBackups.SIZE)
        mysql[co.xenastudios.core.common.family.FamilyBackups.KIND] = co.xenastudios.core.common.family.FamilyBackups.MYSQL
        mysql[co.xenastudios.core.common.family.FamilyBackups.PLUGIN] = "xMoney"
        mysql[co.xenastudios.core.common.family.FamilyBackups.WHERE] = "db.example.com:3306/mc"
        mysql[co.xenastudios.core.common.family.FamilyBackups.PREFIX] = "xmoney_"
        board.map(co.xenastudios.core.common.family.FamilyBackups.MAP).put("xMoney db.example.com:3306/mc", mysql)
        assertEquals(listOf("xMoney: MySQL at db.example.com:3306/mc (xmoney_ tables)"), Databases.Family.external(board))
    }

    @Test
    fun aDatabaseNoXenaPluginHasOpenGoesToSqlite() {
        // Fixtures' plugins/Some/data.db: no plugin of the family has it, so SQLite's own copy takes it.
        val result = Fixtures.repository(repo).use { repository ->
            repository.lock(true).use {
                Backup.run(repository, Fixtures.plan(root, repo), Backup.Pause.None, Databases.Family(board, Fixtures.sqlite), { false }) {}
            }
        }
        assertTrue(result.snapshot.warnings.isEmpty(), "warnings: ${result.snapshot.warnings}")
    }

    private companion object {
        const val LEFT_OUT = "plugins/Some/data.db was left out, because SQLite isn't available to copy it safely."
    }
}
