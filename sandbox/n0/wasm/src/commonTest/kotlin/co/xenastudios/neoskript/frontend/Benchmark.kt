/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend

import co.xenastudios.neoskript.frontend.pattern.CompiledPattern
import co.xenastudios.neoskript.frontend.pattern.PatternCompiler
import co.xenastudios.neoskript.frontend.pattern.PatternIndex
import co.xenastudios.neoskript.frontend.pattern.PlaceholderResolver
import co.xenastudios.neoskript.frontend.source.Text
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Not a real benchmark (no JMH, Node for both Wasm and JS): compiles Skript 2.17's 2,127 patterns and
 * matches the website examples' statement lines against all of them through the index, with a resolver
 * that takes any text, and prints the best of several rounds.
 */
class Benchmark {

    private val anything = PlaceholderResolver { input, start, end, _ -> input.substring(start, end) }

    private fun compileAll(): List<CompiledPattern> = SKRIPT_PATTERNS.map { PatternCompiler.compile(it) }

    private fun matchAll(index: PatternIndex<Int>): Int {
        var matches = 0
        for (line in SAMPLE_LINES) {
            val lower = Text.lower(line)
            for (entry in index.candidates(line)) {
                if (entry.pattern.couldMatch(lower) && entry.pattern.match(line, anything) != null) matches++
            }
        }
        return matches
    }

    @Test
    fun benchmark() {
        val rounds = 15
        var compileBest = Long.MAX_VALUE
        var matchBest = Long.MAX_VALUE
        var matches = 0
        repeat(rounds) {
            val t0 = TimeSource.Monotonic.markNow()
            val patterns = compileAll()
            compileBest = minOf(compileBest, t0.elapsedNow().inWholeMicroseconds)
            val index = PatternIndex<Int>()
            patterns.forEachIndexed { i, p -> index.add(p, i) }
            val t1 = TimeSource.Monotonic.markNow()
            matches = matchAll(index)
            matchBest = minOf(matchBest, t1.elapsedNow().inWholeMicroseconds)
        }
        println(
            "BENCH $target | compile ${SKRIPT_PATTERNS.size} patterns ${compileBest / 1000.0} ms" +
                " | match ${SAMPLE_LINES.size} lines ${matchBest / 1000.0} ms ($matches matches) | best of $rounds",
        )
        assertTrue(matches > 0)
    }
}
