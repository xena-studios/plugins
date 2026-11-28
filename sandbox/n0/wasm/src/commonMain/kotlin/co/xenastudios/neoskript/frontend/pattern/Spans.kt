/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.frontend.pattern

import co.xenastudios.neoskript.frontend.source.Text

/**
 * Finds where an expression starting at some position could end. A placeholder's text can't end inside a
 * string, a `{variable}`, parentheses or square brackets, and can't run past a closing bracket it didn't
 * open.
 */
public object Spans {

    private enum class Context { CODE, STRING, VARIABLE, EXPRESSION, PARENTHESES, BRACKETS }

    /**
     * Every index `end > start`, in increasing order, such that `input[start, end)` is balanced and
     * doesn't start or end with whitespace, considering only `input[start, limit)`.
     */
    public fun candidateEnds(input: String, start: Int, limit: Int = input.length): IntArray {
        if (start >= limit || Text.isWhitespace(input[start])) {
            return IntArray(0)
        }
        val ends = IntArray(limit - start)
        var count = 0
        val contexts = ArrayDeque<Context>()
        contexts.addLast(Context.CODE)
        var i = start
        while (i < limit) {
            val c = input[i]
            val next = if (i + 1 < limit) input[i + 1] else '\u0000'
            when (val top = contexts.last()) {
                Context.CODE, Context.EXPRESSION, Context.PARENTHESES, Context.BRACKETS -> when {
                    c == '"' -> contexts.addLast(Context.STRING)
                    c == '{' -> contexts.addLast(Context.VARIABLE)
                    c == '(' -> contexts.addLast(Context.PARENTHESES)
                    c == '[' -> contexts.addLast(Context.BRACKETS)
                    c == ')' || c == ']' -> {
                        if (top != (if (c == ')') Context.PARENTHESES else Context.BRACKETS)) {
                            return ends.copyOf(count)
                        }
                        contexts.removeLast()
                    }
                    c == '%' && top == Context.EXPRESSION -> contexts.removeLast()
                    c == '}' && top == Context.CODE -> return ends.copyOf(count)
                }
                Context.STRING -> when {
                    c == '"' -> if (next == '"') i++ else contexts.removeLast()
                    c == '%' -> if (next == '%') i++ else contexts.addLast(Context.EXPRESSION)
                }
                Context.VARIABLE -> when {
                    c == '}' -> contexts.removeLast()
                    c == '{' -> contexts.addLast(Context.VARIABLE)
                    c == '%' -> if (next == '%') i++ else contexts.addLast(Context.EXPRESSION)
                }
            }
            if (contexts.size == 1 && !Text.isWhitespace(input[i])) {
                ends[count++] = i + 1
            }
            i++
        }
        return ends.copyOf(count)
    }
}
