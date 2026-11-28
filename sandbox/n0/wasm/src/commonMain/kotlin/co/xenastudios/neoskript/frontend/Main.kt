/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend

import co.xenastudios.neoskript.frontend.parse.Literals
import co.xenastudios.neoskript.frontend.pattern.PatternCompiler
import co.xenastudios.neoskript.frontend.pattern.PlaceholderResolver

/**
 * The executables' entry point, only so each target's production build keeps the slice (dead code
 * elimination drops what nothing reaches) and its size can be measured.
 */
public fun main() {
    val pattern = PatternCompiler.compile("[the] (health|hp) of %players% [to <\\d+>]")
    val result = pattern.match("the health of player to 5", PlaceholderResolver { input, start, end, _ -> input.substring(start, end) })
    println("$result ${Literals.numberType("1.5")} ${Literals.isWords("diamond sword")} ${Literals.normalize(" The  X")}")
}
