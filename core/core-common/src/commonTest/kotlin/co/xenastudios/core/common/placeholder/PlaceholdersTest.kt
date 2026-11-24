package co.xenastudios.core.common.placeholder

import co.xenastudios.core.api.PlayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PlaceholdersTest {

    private val steve = PlayerRef.of(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    private val balances = mutableMapOf(steve to 120)

    private val set = placeholders("xmoney") {
        player("balance", "Your balance.", "1,250") { balances[it]?.toString() }
        global("players", "Players with a balance.", "12") { balances.size.toString() }
    }

    @Test
    fun resolvesByNameFromTheRunningState() {
        assertEquals("120", set.resolve("balance", steve))
        balances[steve] = 130
        assertEquals("130", set.resolve("BALANCE", steve))
        assertEquals("1", set.resolve("players", null))
        assertNull(set.resolve("balance", null), "a player's placeholder has no value without one")
        assertNull(set.resolve("nope", steve))
    }

    @Test
    fun eachPlaceholderPluginWritesItItsWay() {
        assertEquals(
            mapOf("PlaceholderAPI" to "%xmoney_balance%", "MiniPlaceholders" to "<xmoney_balance>", "Text Placeholder API" to "%xmoney:balance%"),
            set.forms(set["balance"]!!),
        )
    }

    @Test
    fun namesAreCheckedAsTheyreDeclared() {
        assertFailsWith<IllegalArgumentException> { placeholders("xmoney") { global("Top Player", "", "") { null } } }
        assertFailsWith<IllegalArgumentException> {
            placeholders("xmoney") {
                global("a", "", "") { null }
                global("a", "", "") { null }
            }
        }
        assertFailsWith<IllegalArgumentException> { placeholders("XMoney") {} }
    }
}
