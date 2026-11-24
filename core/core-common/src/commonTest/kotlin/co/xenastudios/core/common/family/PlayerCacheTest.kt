package co.xenastudios.core.common.family

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.SqlValue
import co.xenastudios.core.common.i18n.LanguageSettings
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageDrawer
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.StorageError
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.text.PlainRenderer
import co.xenastudios.core.testing.FakeDatabase
import co.xenastudios.core.testing.FakeFamily
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.ok
import co.xenastudios.core.testing.row
import co.xenastudios.core.testing.settled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PlayerCacheTest {
    private val steve = PlayerRef.of(Uuid.parse("8667ba71-b85a-4004-af54-457a9734eed7"))
    private val alex = PlayerRef.of(Uuid.parse("ec561538-f3fd-461d-aff5-086b22154bce"))

    private fun cache(platform: FakePlatform, store: PlayerStore = PlayerStore.None, plugin: String = "xtest", capacity: Int = 100) =
        PlayerCache(platform.family.board, store, platform.scheduler, platform.clock, platform.log, plugin, capacity)

    @Test
    fun pluginsOnOneServerShareOneCache() {
        val server = FakeFamily()
        val bans = FakePlatform(family = server)
        val chat = FakePlatform(family = server)
        val bansCache = cache(bans, plugin = "xbans")
        val chatCache = cache(chat, plugin = "zchat")

        bansCache.joined(steve, "Steve", "de_de")
        chatCache.joined(steve, "Steve", "de_de")
        val seen = assertNotNull(chatCache[steve])
        assertEquals("Steve", seen.name)
        assertTrue(seen.online)
        assertEquals(bans.clock.now(), seen.lastSeen)
        assertEquals(steve, bansCache.find("STEVE")?.player)

        chatCache.setVanished(steve, true)
        assertTrue(bansCache.isVanished(steve))

        bans.scheduler.advance(5.minutes)
        bansCache.left(steve)
        assertFalse(chatCache[steve]!!.online)
        assertEquals(bans.clock.now(), chatCache[steve]!!.lastSeen)
        assertNull(chatCache[alex])
    }

    @Test
    fun aRenameMovesTheName() {
        val platform = FakePlatform()
        val cache = cache(platform)
        cache.joined(steve, "Steve", null)
        cache.left(steve)
        cache.joined(steve, "Steve2", null)
        assertNull(cache.find("steve"))
        assertEquals(steve, cache.find("steve2")?.player)
        // Someone else took the old name: it's theirs.
        cache.joined(alex, "Steve", null)
        assertEquals(alex, cache.find("Steve")?.player)
    }

    @Test
    fun itIsWhereMessagesFindAPlayersLanguage() {
        val platform = FakePlatform()
        val cache = cache(platform)
        val heard = mutableListOf<PlayerRef>()
        cache.onLanguageChange { heard += it }
        cache.joined(steve, "Steve", "de_de")
        assertEquals("de_de", cache.clientLocale(steve))
        assertNull(cache.clientLocale(alex))
        cache.languageChanged(steve, "fr_fr")
        cache.languageChanged(steve, "fr_fr")
        assertEquals(listOf(steve), heard, "told once, when it changed")
        cache.joined(steve, "Steve", null)
        assertEquals("fr_fr", cache.clientLocale(steve), "a join before the client says keeps the last language")

        val drawer = MessageDrawer(Translations.of(MessageCatalog.of(co.xenastudios.core.common.i18n.CoreMessages), emptyMap(), LanguageSettings.DEFAULT), cache, PlainRenderer)
        assertEquals("en", drawer.languageOf(steve).tag.toString(), "no French file: default-language")
        assertEquals("en", drawer.languageOf(null).tag.toString())
    }

    @Test
    fun memoryIsBoundedAndKeepsWhoIsOnline() {
        val platform = FakePlatform()
        val cache = cache(platform, capacity = 10)
        val online = PlayerRef.of(Uuid.fromLongs(0, 1))
        cache.joined(online, "Online", null)
        for (i in 2..11) {
            platform.scheduler.advance(1.minutes)
            val player = PlayerRef.of(Uuid.fromLongs(0, i.toLong()))
            cache.joined(player, "P$i", null)
            cache.left(player)
        }
        assertEquals(9, cache.all().size, "down to nine tenths once it's past ten")
        assertNotNull(cache[online], "online players stay")
        assertNull(cache[PlayerRef.of(Uuid.fromLongs(0, 2))], "the least recently seen went first")
        assertNull(cache.find("P2"))
        assertNotNull(cache[PlayerRef.of(Uuid.fromLongs(0, 11))])
    }

    @Test
    fun withoutADatabaseItIsSavedToAFileAndLoadedOnTheNextStart() {
        val server = FakeFamily()
        val bans = FakePlatform(family = server)
        val chat = FakePlatform(family = server)
        val bansCache = cache(bans, PlayerStore.InFile(server.folder), "xbans")
        val chatCache = cache(chat, PlayerStore.InFile(server.folder), "zchat")
        bansCache.joined(steve, "Steve", "de_de")
        chatCache.joined(alex, "Alex", null)
        chatCache.setVanished(alex, true)
        assertEquals(emptyList(), server.folder.paths, "saved off the server's threads")
        bans.scheduler.runPending()
        chat.scheduler.runPending()
        val text = assertNotNull(server.folder.readText(PlayerCache.FILE).ok())
        assertTrue("tSteve" in text && "tAlex" in text, text)

        // The next start: a new board, loaded once from the file by the first plugin.
        val restarted = FakeFamily()
        restarted.folder.write(PlayerCache.FILE, text.encodeToByteArray())
        val first = FakePlatform(family = restarted)
        val second = FakePlatform(family = restarted)
        val loading = cache(first, PlayerStore.InFile(restarted.folder), "xbans").load()
        val other = cache(second, PlayerStore.InFile(restarted.folder), "zchat")
        assertTrue(other.load().settled() == Unit, "another plugin already loads it")
        first.scheduler.runPending()
        loading.settled()
        val back = assertNotNull(other[alex])
        assertEquals("Alex", back.name)
        assertTrue(back.vanished)
        assertFalse(back.online, "nobody is online after a restart")
        assertEquals("de_de", other.clientLocale(steve))
    }

    @Test
    fun aSaveThatFailsIsTriedWithTheNextChange() {
        val platform = FakePlatform()
        val cache = cache(platform, PlayerStore.InFile(platform.family.folder))
        platform.family.folder.failure = StorageError.Kind.IO
        cache.joined(steve, "Steve", null)
        platform.scheduler.runPending()
        assertEquals(1, platform.log.lines.size)
        platform.family.folder.failure = null
        cache.joined(alex, "Alex", null)
        platform.scheduler.runPending()
        val text = platform.family.folder.readText(PlayerCache.FILE).ok()!!
        assertTrue("tSteve" in text && "tAlex" in text, "the failed change went with the next: $text")
    }

    @Test
    fun withTheSharedDatabaseEachChangeIsARowInTheFamilysTable() {
        val platform = FakePlatform()
        val database = FakeDatabase(prefix = "xbans_")
        val cache = cache(platform, PlayerStore.InDatabase(database))
        cache.load()
        platform.scheduler.runPending()
        val log = database.session.log
        assertTrue(log.any { it.startsWith("CREATE TABLE IF NOT EXISTS xena_players") }, "the family's table, not the plugin's: $log")
        assertTrue(log.any { "xena_meta" in it })
        log.clear()

        cache.joined(steve, "Steve", "de_de")
        cache.forget(alex)
        platform.scheduler.runPending()
        assertTrue(log.any { it.startsWith("INSERT INTO xena_players") && "Text(value=Steve)" in it }, "$log")
        log.clear()
        cache.forget(steve)
        platform.scheduler.runPending()
        assertTrue(log.any { it.startsWith("DELETE FROM xena_players") }, "$log")
    }

    @Test
    fun aPlayerNotInMemoryIsReadFromTheSharedDatabase() {
        val platform = FakePlatform()
        val database = FakeDatabase()
        database.session.answer = { statement ->
            if ("FROM xena_players" in statement.sql) {
                Outcome.Ok(listOf(row(
                    "uuid" to SqlValue.Bytes(alex.uuid.toByteArray()), "name" to SqlValue.Text("Alex"), "lookup" to SqlValue.Text("alex"),
                    "last_seen" to SqlValue.Integer(Instant.parse("2025-06-01T00:00:00Z").toEpochMilliseconds()),
                    "language" to SqlValue.Null, "vanished" to SqlValue.Integer(0),
                )))
            } else {
                Outcome.Ok(emptyList())
            }
        }
        val cache = cache(platform, PlayerStore.InDatabase(database))
        assertNull(cache[alex])
        val found = assertNotNull(cache.lookup("ALEX").settled().ok())
        assertEquals(alex, found.player)
        assertEquals(Instant.parse("2025-06-01T00:00:00Z"), found.lastSeen)
        assertNotNull(cache[alex], "kept in memory once read")
        assertEquals(found, cache.lookup(alex).settled().ok())
    }
}
