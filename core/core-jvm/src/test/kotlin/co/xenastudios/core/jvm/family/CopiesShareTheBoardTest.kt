package co.xenastudios.core.jvm.family

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.family.PlayerCache
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two plugins on one server: each with its own copy of the core and of Kotlin, in class loaders
 * of their own that can't see each other's classes, as two relocated plugins on Spigot are.
 * They share only the board's root, a JDK map, and still see one player cache.
 */
class CopiesShareTheBoardTest {

    // A class loader with its own copy of Kotlin, core-api, core-common, core-jvm and these tests,
    // whose parent knows only the JDK.
    private fun copy(): ClassLoader {
        val sources = listOf(KotlinVersion::class.java, PlayerRef::class.java, PlayerCache::class.java, JvmFamilyBoard::class.java, FamilyProbe::class.java)
            .map { it.protectionDomain.codeSource.location }
            .distinct()
        return URLClassLoader(sources.toTypedArray(), ClassLoader.getSystemClassLoader().parent)
    }

    private class Plugin(loader: ClassLoader, root: ConcurrentMap<String, Any>, name: String) {
        private val type = loader.loadClass(FamilyProbe::class.java.name)
        private val probe = type.getConstructor(ConcurrentMap::class.java, String::class.java).newInstance(root, name)

        fun call(method: String, vararg args: String): Any? =
            type.getMethod(method, *args.map { String::class.java }.toTypedArray()).invoke(probe, *args)
    }

    @Test
    fun twoCopiesOfTheCoreSeeOneCache() {
        val root = JvmFamilyBoard.newRoot()
        val bans = Plugin(copy(), root, "xbans")
        val chat = Plugin(copy(), root, "zchat")
        assertNotEquals(bans.call("loaders"), chat.call("loaders"), "each has its own copy of the core and of Kotlin")

        val steve = "8667ba71-b85a-4004-af54-457a9734eed7"
        bans.call("joined", steve, "Steve", "de_de")
        assertEquals("Steve|de_de|false", chat.call("describe", steve))
        assertEquals(steve, chat.call("find", "steve"))

        chat.call("vanish", steve)
        assertEquals("Steve|de_de|true", bans.call("describe", steve))
        assertNull(bans.call("describe", "00000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun theRegistryFindsTheBoardAmongOtherMaps() {
        val other = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val root = JvmFamilyBoard.newRoot()
        assertTrue(JvmFamilyBoard.find(listOf(other, root, null)).root === root)
        assertTrue(JvmFamilyBoard.find(listOf(other)).root !== other, "none is the board: a new one")
    }
}
