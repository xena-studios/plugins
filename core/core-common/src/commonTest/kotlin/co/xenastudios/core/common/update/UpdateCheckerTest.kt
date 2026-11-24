package co.xenastudios.core.common.update

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class UpdateCheckerTest {
    private val platform = FakePlatform()
    private var listed: Outcome<List<Release>, String> = Outcome.Ok(
        listOf(
            Release("3.0.0", UpdateChannel.RELEASE, "https://example.org/3.0.0"),
            Release("3.1.0-beta.1", UpdateChannel.BETA, "https://example.org/3.1.0-beta.1"),
            Release("3.2.0-alpha.2", UpdateChannel.ALPHA, "https://example.org/3.2.0-alpha.2"),
        ),
    )
    private var asked = 0
    private val sent = mutableListOf<Pair<PlayerRef?, String>>()
    private val admin = PlayerRef.of(Uuid.random())
    private val player = PlayerRef.of(Uuid.random())

    private val notices = object : UpdateNotices {
        override fun send(viewer: PlayerRef?, message: MessageKey, placeholders: Map<String, Placeholder>) {
            assertEquals(CoreMessages.updateAvailable, message)
            sent += viewer to (placeholders.getValue("latest") as Placeholder.Text).text
        }
        override fun mayKnow(player: PlayerRef): Boolean = player == admin
    }

    private fun checker(current: String = "2.0.0") = UpdateChecker(
        platform, "xTest", current,
        ReleaseSource { asked++; listed },
        notices, offline = false,
    )

    @Test
    fun checksOnceAtStartThenOnceADayOffTheServerThreads() {
        val checker = checker()
        checker.apply(UpdateSettings.DEFAULT)
        assertEquals(0, asked, "never on the thread that started it")
        platform.scheduler.runPending()
        assertEquals(1, asked)
        platform.scheduler.advance(23.hours)
        assertEquals(1, asked)
        platform.scheduler.advance(1.hours)
        assertEquals(2, asked)
        // Started again by a reload with the same settings: still one task.
        checker.apply(UpdateSettings.DEFAULT)
        platform.scheduler.advance(1.days)
        assertEquals(3, asked)
        checker.close()
        platform.scheduler.advance(3.days)
        assertEquals(3, asked)
    }

    @Test
    fun followsTheChannelTheOwnerPicked() {
        val checker = checker()
        checker.apply(UpdateSettings(true, UpdateChannel.RELEASE))
        platform.scheduler.runPending()
        assertEquals("3.0.0", checker.latest?.version)
        checker.apply(UpdateSettings(true, UpdateChannel.BETA))
        assertEquals("3.1.0-beta.1", checker.latest?.version)
        checker.apply(UpdateSettings(true, UpdateChannel.ALPHA))
        assertEquals("3.2.0-alpha.2", checker.latest?.version)
        // The console heard of each once, as the channel brought it in.
        assertEquals(listOf<Pair<PlayerRef?, String>>(null to "3.0.0", null to "3.1.0-beta.1", null to "3.2.0-alpha.2"), sent)
    }

    @Test
    fun saysItOnceInTheConsoleAndToAdminsAsTheyJoin() {
        val checker = checker()
        checker.apply(UpdateSettings.DEFAULT)
        platform.scheduler.runPending()
        platform.scheduler.advance(2.days)
        assertEquals(listOf<Pair<PlayerRef?, String>>(null to "3.0.0"), sent)
        checker.joined(player)
        checker.joined(admin)
        assertEquals(listOf(null to "3.0.0", admin to "3.0.0"), sent)
    }

    @Test
    fun nothingNewerIsNothingToSay() {
        val checker = checker(current = "3.0.0")
        checker.apply(UpdateSettings.DEFAULT)
        platform.scheduler.runPending()
        checker.joined(admin)
        assertNull(checker.latest)
        assertTrue(sent.isEmpty())
        // A prerelease of the same version is older than the release.
        val beta = checker(current = "3.0.0-beta.3")
        beta.apply(UpdateSettings.DEFAULT)
        platform.scheduler.runPending()
        assertEquals("3.0.0", beta.latest?.version)
    }

    @Test
    fun aFailedCheckIsKeptQuietlyAndTriedTomorrow() {
        listed = Outcome.Err("Modrinth can't be reached: timed out")
        val checker = checker()
        checker.apply(UpdateSettings.DEFAULT)
        platform.scheduler.runPending()
        assertEquals("Modrinth can't be reached: timed out", checker.problem)
        assertTrue(platform.log.lines.isEmpty() && sent.isEmpty())
        listed = Outcome.Ok(listOf(Release("2.0.1", UpdateChannel.RELEASE, "u")))
        platform.scheduler.advance(1.days)
        assertNull(checker.problem)
        assertEquals("2.0.1", checker.latest?.version)
    }

    @Test
    fun turnedOffItStopsAskingAndForgets() {
        val checker = checker()
        checker.apply(UpdateSettings.DEFAULT)
        platform.scheduler.runPending()
        checker.apply(UpdateSettings(false, UpdateChannel.RELEASE))
        assertNull(checker.latest)
        platform.scheduler.advance(3.days)
        assertEquals(1, asked)
        checker.joined(admin)
        assertEquals(1, sent.size)
    }

    @Test
    fun offlineNeverAsks() {
        val checker = UpdateChecker(platform, "xTest", "1.0.0", ReleaseSource { asked++; listed }, notices, offline = true)
        checker.apply(UpdateSettings.DEFAULT)
        platform.scheduler.advance(2.days)
        assertEquals(0, asked)
        assertEquals(0, platform.scheduler.pending)
    }

    @Test
    fun theNoticeCarriesItsPlaceholders() {
        val checker = checker()
        val placeholders = checker.placeholders(Release("3.0.0", UpdateChannel.RELEASE, "https://modrinth.com/project/xtest/version/abc"))
        assertEquals(
            mapOf("plugin" to "xTest", "current" to "2.0.0", "latest" to "3.0.0", "url" to "https://modrinth.com/project/xtest/version/abc"),
            placeholders.mapValues { (it.value as Placeholder.Text).text },
        )
    }

    @Test
    fun comparesVersionsAsSemverDoes() {
        val ordered = listOf("1.0.0-alpha.1", "1.0.0-alpha.2", "1.0.0-beta.1", "1.0.0-beta.10", "1.0.0", "1.0.1", "1.2", "1.10.0", "2.0.0+fabric-1.21.11")
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals(i.compareTo(j), Versions.compare(ordered[i], ordered[j]).coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
        }
        assertEquals(0, Versions.compare("2.0.0+fabric-1.21.11", "2.0.0+fabric-26.3"))
        assertEquals(UpdateChannel.ALPHA, UpdateChannel.of("3.0.0-alpha.1"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.of("3.0.0-rc.1"))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.of("3.0.0+fabric-26.3"))
    }
}
