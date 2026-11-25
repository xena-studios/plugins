package co.xenastudios.core.common.permission

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionResolverTest {
    private object Perms : PermissionSet("xtest") {
        val use: Permission = permission("use", "Use it.", PermissionDefault.EVERYONE)
        val give: Permission = permission("give", "Give.")
        val hidden: Permission = permission("hidden", "No one.", PermissionDefault.NOBODY)
        val newcomer: Permission = permission("newcomer", "Not for ops.", PermissionDefault.NOT_OP)
        val all: Permission = permission("*", "Everything.", children = grants(give, hidden))
        val muted: Permission = permission("muted", "Takes use away.", PermissionDefault.NOBODY, children = mapOf("xtest.use" to false))
    }

    private val resolver = PermissionResolver().apply { sync(Perms.permissions) }
    private val none: (String) -> Boolean? = { null }

    @Test
    fun withNoPermissionModTheDeclaredDefaultsDecideByOperator() {
        assertTrue(resolver.has(Perms.use.node, operator = false, none))
        assertFalse(resolver.has(Perms.give.node, operator = false, none))
        assertTrue(resolver.has(Perms.give.node, operator = true, none))
        assertTrue(resolver.has(Perms.newcomer.node, operator = false, none))
        assertFalse(resolver.has(Perms.newcomer.node, operator = true, none))
    }

    @Test
    fun anUndeclaredNodeIsForOperators() {
        assertFalse(resolver.has("other.thing", operator = false, none))
        assertTrue(resolver.has("other.thing", operator = true, none))
    }

    @Test
    fun thePermissionModsAnswerComesFirst() {
        assertFalse(resolver.has(Perms.use.node, operator = true) { if (it == "xtest.use") false else null })
        assertTrue(resolver.has(Perms.give.node, operator = false) { if (it == "xtest.give") true else null })
    }

    @Test
    fun aParentGrantsOrTakesAwayItsChildren() {
        // An operator has xtest.* by default, and so every child it grants, even a NOBODY one.
        assertTrue(resolver.has(Perms.hidden.node, operator = true, none))
        assertTrue(resolver.has(Perms.give.node, operator = false) { if (it == "xtest.*") true else null })
        assertFalse(resolver.has(Perms.use.node, operator = false) { if (it == "xtest.muted") true else null })
        // A child set explicitly beats its parent.
        assertFalse(resolver.has(Perms.give.node, operator = true) { if (it == "xtest.give") false else null })
    }

    @Test
    fun childrenThatLoopStop() {
        val loop = object : PermissionSet("xloop") {
            val a: Permission = permission("a", "A.", PermissionDefault.NOBODY, children = mapOf("xloop.b" to true))
            val b: Permission = permission("b", "B.", PermissionDefault.NOBODY, children = mapOf("xloop.a" to true))
        }
        val looping = PermissionResolver().apply { sync(loop.permissions) }
        assertFalse(looping.has("xloop.a", operator = true, none))
    }

    @Test
    fun aSyncReplacesWhatWasDeclared() {
        val fresh = PermissionResolver().apply { sync(Perms.permissions) }
        fresh.sync(emptyList())
        // xtest.use was for everyone; now it's undeclared, so for operators.
        assertFalse(fresh.has(Perms.use.node, operator = false, none))
    }
}
