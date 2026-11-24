package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class R8ShrinkTest {
    /** A class file's start: magic, version, the constant pool's count, then constant #1. */
    private fun classWith(first: String, rest: ByteArray = byteArrayOf(7, 0, 1)): ByteArray {
        val text = first.toByteArray()
        return byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 52, 0, 3, 1) +
            byteArrayOf((text.size shr 8).toByte(), text.size.toByte()) + text + rest
    }

    @Test
    fun `empties R8's marker and keeps every index`() {
        val marker = "~~R8{\"backend\":\"cf\",\"r8-mode\":\"full\",\"version\":\"9.5.33\"}"
        assertArrayEquals(classWith(""), R8Shrink.withoutMarker(classWith(marker)))
    }

    @Test
    fun `leaves a class without the marker alone`() {
        val bytes = classWith("co/xenastudios/Some")
        assertSame(bytes, R8Shrink.withoutMarker(bytes))
    }
}
