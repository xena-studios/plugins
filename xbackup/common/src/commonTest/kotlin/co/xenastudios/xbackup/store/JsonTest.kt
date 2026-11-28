package co.xenastudios.xbackup.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What the cloud drives send: a hostile or broken reply is an IOException, never a crash (1.x's JsonTest). */
class JsonTest {
    @Test
    fun readsWhatTheDrivesSend() {
        val reply = Json.obj("{\"name\":\"a\\u00e9\",\"size\":12,\"value\":[{\"x\":true}],\"n\":null}")
        assertEquals("aé", reply["name"])
        assertEquals(12L, (reply["size"] as Number).toLong())
        assertEquals(listOf(mapOf("x" to true)), reply["value"])
        assertEquals(null, reply["n"])
    }

    @Test
    fun deepNestingAndBadEscapesAreRefused() {
        assertFailsWith<IOException> { Json.parse("[".repeat(100_000) + "]".repeat(100_000)) }
        assertFailsWith<IOException> { Json.parse("\"\\uZZZZ\"") }
        assertFailsWith<IOException> { Json.parse("\"\\u12\"") }
        assertEquals(listOf(emptyList<Any>()), Json.parse("[[]]"))
    }
}
