package co.xenastudios.xvote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xvote.config.XVoteConfig
import co.xenastudios.xvote.vote.IncomingVote
import co.xenastudios.xvote.vote.Names
import co.xenastudios.xvote.vote.Periods
import co.xenastudios.xvote.vote.Zone
import co.xenastudios.xvote.api.VoteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** xVote's rules with no server: periods, names, the network's text made safe, and JSON. On the JVM and wasmWasi. */
class RulesTest {
    // 2026-01-05 was a Monday.
    private val monday = 1767571200000L
    private val day = 86_400_000L

    @Test
    fun periodsInUtc() {
        val periods = Periods(Zone.UTC, XVoteConfig.WeekDay.MONDAY)
        assertEquals(monday, periods.dayStart(monday + 5 * 3_600_000))
        assertEquals(monday + day, periods.dayEnd(monday))
        assertEquals(monday, periods.weekStart(monday + 6 * day))
        assertEquals(monday + 7 * day, periods.weekStart(monday + 7 * day))
        // 2026-01-01.
        assertEquals(monday - 4 * day, periods.monthStart(monday + 10 * day))
        assertEquals(2026 * 12, periods.monthKey(monday))
        val sunday = Periods(Zone.UTC, XVoteConfig.WeekDay.SUNDAY)
        assertEquals(monday - day, sunday.weekStart(monday))
    }

    @Test
    fun periodsInAZoneAheadOfUtc() {
        // UTC+2 the whole time: midnight there is 22:00 UTC the day before.
        val plusTwo = object : Zone {
            override val id: String = "+2"
            override fun offsetMillis(epochMillis: Long): Long = 2 * 3_600_000L
        }
        val periods = Periods(plusTwo, XVoteConfig.WeekDay.MONDAY)
        assertEquals(monday - 2 * 3_600_000L, periods.dayStart(monday))
        assertEquals(periods.day(monday - 3 * 3_600_000L) + 1, periods.day(monday - 1 * 3_600_000L))
    }

    @Test
    fun streaks() {
        assertEquals(1, Periods.nextStreak(10, 0, 11))
        assertEquals(3, Periods.nextStreak(10, 3, 10))
        assertEquals(4, Periods.nextStreak(10, 3, 11))
        assertEquals(1, Periods.nextStreak(10, 3, 12))
        assertEquals(3, Periods.nextStreak(10, 3, 9))
    }

    @Test
    fun names() {
        assertEquals("Steve", Names.clean(" Steve ", "."))
        assertEquals("Some_Gamer", Names.clean("Some Gamer", "."))
        assertEquals(".Bedrock", Names.clean(".Bedrock", "."))
        assertNull(Names.clean("Not-a-name", "."))
        assertNull(Names.clean("ThisNameIsWayTooLong", "."))
        assertEquals(listOf("Steve", ".Steve"), Names.lookups("Steve", "."))
        assertEquals(listOf(".steve", "steve"), Names.matchKeys(".Steve", "."))
    }

    @Test
    fun networkTextLosesItsControlCharacters() {
        val vote = IncomingVote("List\nFAKE LOG LINE", "Steve\u001B[31m", "1.2.3.4 ", "x".repeat(100), VoteSource.API, 0)
        assertEquals("ListFAKE LOG LINE", vote.site)
        assertEquals("Steve[31m", vote.username)
        assertEquals("1.2.3.4", vote.address)
        assertEquals(IncomingVote.MAX_FIELD, vote.timestamp.length)
        assertEquals("Planet_Minecraft_x_y", Rewards.safe("Planet Minecraft;x&y"))
        assertEquals("_", Rewards.safe(""))
    }

    @Test
    fun json() {
        val parsed = Json.parse("""{"a": [1, 2.5, "x\né"], "b": {"c": null, "d": true}, "t": 1731801600000}""") as Map<*, *>
        assertEquals(listOf(Json.Number("1"), Json.Number("2.5"), "x\né"), parsed["a"])
        assertEquals(1731801600000L, (parsed["t"] as Json.Number).toLongOrNull())
        assertEquals(mapOf("c" to null, "d" to true), parsed["b"])
        assertEquals("""{"a":"q\"\\","n":[1,true,null]}""", Json.write(linkedMapOf("a" to "q\"\\", "n" to listOf(1, true, null))))
        // NuVotifier's forwarded votes: objects back to back.
        assertEquals(2, Json.parseAll("""{"username":"a"}{"username":"b"}""").size)
        for (bad in listOf("", "{", "[1,", "{\"a\" 1}", "tru", "\"abc", "{} x", "[" .repeat(100) + "]".repeat(100))) {
            assertFailsWith<Json.Malformed>(bad) { Json.parse(bad) }
        }
    }

    @Test
    fun aTestPlayerRefIsAUuid() {
        assertTrue(PlayerRef.parseOrNull("5627dd98-e6be-3c21-b8a8-e92344183641") != null)
    }
}
