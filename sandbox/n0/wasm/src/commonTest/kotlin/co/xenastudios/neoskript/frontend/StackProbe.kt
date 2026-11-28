/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend

import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Not a test: whether running out of stack can be caught, as neoskript-frontend's StatementParser and
 * ScriptLoader catch StackOverflowError to report a line that's too deep. Ignored, as on Wasm it
 * can't be caught and fails the run: remove @Ignore and run it alone (`--tests '*.StackProbe'`).
 */
class StackProbe {

    private fun depth(n: Int): Int = if (n == 0) 0 else 1 + depth(n - 1)

    @Ignore
    @Test
    fun stackOverflow() {
        val result = try {
            "returned ${depth(100_000_000)}"
        } catch (e: Throwable) {
            "caught ${e::class.simpleName}"
        }
        println("STACK $target | $result")
    }
}
