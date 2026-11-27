package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.ip.Sha256
import co.xenastudios.xbans.model.Active
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.model.Length
import co.xenastudios.xbans.model.Replacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** xBans' rules that need no server, on the JVM and on Wasm: 1.x's tests of them, ported. */
class RulesTest {
    private val steve = PlayerRef.parseOrNull("069a79f4-44e9-4726-a5be-fca90e38aaf5")!!
    private val alex = PlayerRef.parseOrNull("853c80ef-3c37-49fd-aa49-938b674adae6")!!

    private fun entry(id: Long, type: PunishmentType, target: PlayerRef?, ip: String? = null, expires: Long? = null, scope: String = Punishment.GLOBAL, weight: Int = 0) =
        Entry(Punishment(id, type, target, target?.toString() ?: "ip", ip != null, "r", null, "Console", Instant.fromEpochMilliseconds(0),
            expires?.let { Instant.fromEpochMilliseconds(it) }, scope, false, null, null), ip, weight)

    @Test
    fun lengthsAsStaffTypeThem() {
        assertEquals(30 * 60_000L, Length.parse("30m")!!.millis)
        assertEquals(86_400_000L + 2 * 3_600_000L, Length.parse("1d2h")!!.millis)
        assertEquals(5_400_000L, Length.parse("1.5h")!!.millis)
        assertEquals(60L * 86_400_000L, Length.parse("2mo")!!.millis)
        assertTrue(Length.parse("perm")!!.permanent)
        assertNull(Length.parse("hacking"))
        assertNull(Length.parse("500ms"))
        assertTrue(Length.read("200y").tooLong)
        assertEquals("2w", Length.parse("14d")!!.typed())
        assertEquals("1d12h", Length.parse("36h")!!.typed())
        assertEquals("1mo 3d", Length.format(33L * 86_400_000L))
        assertTrue(Length.PERMANENT > Length.parse("100y")!!)
    }

    @Test
    fun argumentsTheLiteBansWay() {
        val parsed = Args.parse(listOf("Steve", "-s", "5", "days", "server:survival", "hacking", "for", "xray"), true) { it == "hacking" }!!
        assertTrue(parsed.silent)
        assertEquals("survival", parsed.scope)
        assertEquals(5 * 86_400_000L, parsed.length!!.millis)
        assertEquals("hacking", parsed.template)
        assertEquals("for xray", parsed.reason)
        assertEquals("3dd", Args.parse(listOf("Steve", "3dd", "why"), true) { false }!!.badLength)
        assertEquals("10x why", Args.parse(listOf("Steve", "10x", "why"), true) { false }!!.reason, "not a length: the reason")
        assertEquals("-s really", Args.parse(listOf("Steve", "--", "-s", "really"), true) { false }!!.reason)
        assertEquals("*", Args.parse(listOf("Steve", "-g"), true) { false }!!.scope)
        assertNull(Args.parse(emptyList(), true) { false })
    }

    @Test
    fun hmacSha256AsTheJdkMakesIt() {
        // RFC 4231, test case 2.
        val mac = Sha256.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        // FIPS 180-4's "abc", and a message over one block.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    @Test
    fun ipKeysAsJavaWritesAddresses() {
        assertEquals("203.0.113.9", IpKeys.normalise("203.0.113.9"))
        assertEquals("2001:db8:0:0:0:0:0:1", IpKeys.normalise("2001:DB8::1"))
        assertEquals("203.0.113.9", IpKeys.normalise("::ffff:203.0.113.9"))
        assertFalse(IpKeys.punishable("127.0.0.1"))
        assertFalse(IpKeys.punishable("::1"))
        assertFalse(IpKeys.punishable("0.0.0.0"))
        assertFalse(IpKeys.punishable("256.1.1.1"))
        assertTrue(IpKeys.punishable("198.51.100.7"))
        assertEquals("198.51.x.x", IpKeys.label("198.51.100.7"))
        assertEquals("2001:db8:…", IpKeys.label("2001:db8::1"))
        val salt = IpKeys.parseSalt("00".repeat(32))!!
        val hashed = IpKeys.hashed(salt)
        assertEquals(32, hashed.key("198.51.100.7").length)
        assertEquals(hashed.key("198.51.100.7"), hashed.key("::ffff:198.51.100.7"), "one address, one key")
        assertTrue(IpKeys.label(hashed.key("198.51.100.7")).endsWith("…"))
        assertNull(IpKeys.parseSalt("not hex"))
    }

    /** 1.x's ReplacingTest: the table in decisions/2026-11-18-xbans.md. */
    @Test
    fun replacingFollowsWhoMayLift() {
        val permanent = entry(1, PunishmentType.BAN, steve)
        assertEquals(Replacing.REPLACE, Replacing.of(permanent, steve, null, null, mayLift = true), "permanent twice: the new reason stands")
        val day = entry(2, PunishmentType.BAN, steve, expires = 86_400_000)
        assertEquals(Replacing.REPLACE, Replacing.of(day, steve, null, 2 * 86_400_000L, true), "longer")
        assertEquals(Replacing.REFUSE_LONGER, Replacing.of(day, steve, null, 3_600_000L, true), "shorter")
        val ipBan = entry(3, PunishmentType.BAN, steve, ip = "k")
        assertEquals(Replacing.REFUSE_LONGER, Replacing.of(ipBan, steve, null, null, true), "an account ban never replaces an IP ban; refused if it lasts as long")
        val shortIp = entry(4, PunishmentType.BAN, steve, ip = "k", expires = 1000)
        assertEquals(Replacing.BOTH, Replacing.of(shortIp, steve, null, null, true), "a longer account ban stands beside a shorter IP ban")
        assertEquals(Replacing.REPLACE, Replacing.of(permanent, steve, "k", null, true), "an IP ban over the account's, as long")
        assertEquals(Replacing.REFUSE_OUTRANKED, Replacing.of(permanent, steve, null, null, mayLift = false), "a senior's, by a junior")
        assertEquals(Replacing.BOTH, Replacing.of(day, steve, null, null, mayLift = false), "a junior's longer one stands beside it")
    }

    @Test
    fun whatsInForceInMemory() {
        val active = Active()
        active.add(entry(1, PunishmentType.BAN, steve, expires = 1000))
        active.add(entry(2, PunishmentType.MUTE, alex, ip = "k"))
        active.add(entry(3, PunishmentType.BAN, alex, scope = "survival"))
        active.add(entry(4, PunishmentType.WARN, alex))
        assertNotNull(active.ban(steve, null, "lobby", 500))
        assertNull(active.ban(steve, null, "lobby", 1500), "ended on its own")
        assertNotNull(active.mute(steve, "k", "lobby", 0), "anyone on the IP")
        assertNull(active.ban(alex, null, "lobby", 0), "another server's ban")
        assertNotNull(active.ban(alex, null, "survival", 0))
        assertNull(active.ban(alex, null, null, 0), "the proxy's door: bans on every server only")
        assertEquals(2, active.size, "the warning isn't kept, the ended ban was dropped")
        val sync = active.beginSync()
        active.remove(2)
        val added = sync.finish(listOf(entry(2, PunishmentType.MUTE, alex, ip = "k"), entry(5, PunishmentType.BAN, steve)))
        assertEquals(listOf(5L), added.map { it.id }, "what changed while it read is left as memory has it")
        assertNull(active.byId(3), "what the database no longer has is dropped")
    }

    @Test
    fun datesInTheConfigsPattern() {
        // 2026-01-02 03:04:05 UTC, a Friday.
        val at = Instant.parse("2026-01-02T03:04:05Z").toEpochMilliseconds()
        assertEquals("2026-01-02 03:04", Dates.of("yyyy-MM-dd HH:mm").format(at, 0))
        assertEquals("Fri 2 Jan 26, 3:04 AM", Dates.of("EEE d MMM yy, h:mm a").format(at, 0))
        assertEquals("2026-01-02 05:04", Dates.of("yyyy-MM-dd HH:mm").format(at, 7200))
        assertEquals("at 03h04", Dates.of("'at' HH'h'mm").format(at, 0))
    }
}
