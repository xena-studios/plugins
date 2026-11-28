/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Literals has no test of its own in neoskript-frontend (its callers' tests cover it); these pin what
// its regexes mean on the JVM, so the other targets are held to it.
class LiteralsTest {

    @Test
    fun numbers() {
        assertEquals("integer", Literals.numberType("-1_000"))
        assertEquals("number", Literals.numberType("1.5"))
        assertEquals("number", Literals.numberType("50%"))
        assertEquals("number", Literals.numberType("1.23e+4"))
        assertNull(Literals.numberType("1."))
        // Java's \d is ASCII: an Arabic-Indic digit isn't a number.
        assertNull(Literals.numberType("٣"))
    }

    @Test
    fun timespansAndTimes() {
        assertTrue(Literals.isTimespan("1 minute and 30 Seconds"))
        assertTrue(Literals.isTimespan("a tick"))
        assertTrue(Literals.isTimespan("2 real hours, 3 mc days"))
        assertFalse(Literals.isTimespan("1 minuet"))
        assertTrue(Literals.isBareUnit("TICK"))
        assertTrue(Literals.isTime("12:30 PM"))
        assertTrue(Literals.isTime("6am"))
        assertFalse(Literals.isTime("123:00"))
        assertTrue(Literals.isBoolean("Yes"))
    }

    @Test
    fun words() {
        assertTrue(Literals.isWords("diamond sword"))
        assertTrue(Literals.isWords("\$5"))
        assertTrue(Literals.isWords("20€"))
        assertTrue(Literals.isWords("pépé's hat"))
        assertTrue(Literals.isWords("stone[facing=north]"))
        assertFalse(Literals.isWords("123"))
        assertFalse(Literals.isWords("key: value"))
    }

    @Test
    fun specified() {
        val match = Literals.SPECIFIED.matchEntire("black (wolf variant)")!!
        assertEquals("black", match.groupValues[1])
        assertEquals("wolf variant", match.groupValues[2])
    }

    @Test
    fun normalize() {
        assertEquals("diamond sword", Literals.normalize("  The  Diamond\tSword "))
        // A no-break space stays: Java's \s and strip() don't take it.
        assertEquals("a b", Literals.normalize("a b"))
    }
}
