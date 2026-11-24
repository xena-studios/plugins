package co.xenastudios.core.bukkit

import co.xenastudios.core.bukkit.BukkitServer.Flavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BukkitServerTest {

    private val spigot188 = setOf("org.spigotmc.SpigotConfig", "net.md_5.bungee.api.chat.BaseComponent", "org.bukkit.entity.Player\$Spigot")
    private val spigot121 = spigot188 + "org.bukkit.event.player.PlayerLocaleChangeEvent"
    private val paper121 = spigot121 + "io.papermc.paper.configuration.Configuration"
    private val folia = paper121 + "io.papermc.paper.threadedregions.RegionizedServer"

    private fun detect(classes: Set<String>, version: String, name: String = "CraftBukkit", adventure: Boolean = false) =
        BukkitServer.detect({ it in classes }, version, name, adventure)

    @Test
    fun eachFlavorIsFoundByItsClasses() {
        assertEquals(Flavor.FOLIA, detect(folia, "26.3-R0.1-SNAPSHOT", "Folia", adventure = true).flavor)
        assertEquals(Flavor.PAPER, detect(paper121, "1.21.11-R0.1-SNAPSHOT", "Paper", adventure = true).flavor)
        assertEquals(Flavor.PAPER, detect(spigot188 + "org.github.paperspigot.PaperSpigotConfig", "1.8.8-R0.1-SNAPSHOT", "PaperSpigot").flavor)
        assertEquals(Flavor.PAPER, detect(spigot121 + "com.destroystokyo.paper.PaperConfig", "1.16.5-R0.1-SNAPSHOT", "Paper").flavor)
        assertEquals(Flavor.SPIGOT, detect(spigot188, "1.8.8-R0.1-SNAPSHOT").flavor)
        assertEquals(Flavor.CRAFTBUKKIT, detect(emptySet(), "1.8.8-R0.1-SNAPSHOT").flavor)
    }

    @Test
    fun whatEachServerHas() {
        val old = detect(spigot188, "1.8.8-R0.1-SNAPSHOT")
        assertTrue(old.bungeeChat)
        assertFalse(old.localeApi)
        assertFalse(old.adventure)
        assertFalse(old.atLeast(1, 11))

        val craftbukkit = detect(emptySet(), "1.21.11-R0.1-SNAPSHOT")
        assertFalse(craftbukkit.bungeeChat)

        val paper = detect(paper121, "1.21.11-R0.1-SNAPSHOT", "Paper", adventure = true)
        assertTrue(paper.adventure && paper.localeApi && paper.atLeast(1, 21, 11))
        assertFalse(paper.atLeast(1, 21, 12))
    }

    @Test
    fun namesSayTheBrandAndTheFlavorUnderIt() {
        assertEquals("Spigot 1.8.8", detect(spigot188, "1.8.8-R0.1-SNAPSHOT").toString())
        assertEquals("Paper 1.21.11", detect(paper121, "1.21.11-R0.1-SNAPSHOT", "Paper").toString())
        assertEquals("Purpur 1.21.11 (Paper)", detect(paper121, "1.21.11-R0.1-SNAPSHOT", "Purpur").toString())
        assertEquals("Folia 26.3", detect(folia, "26.3-R0.1-SNAPSHOT", "Folia").toString())
    }

    @Test
    fun versionsCompareByEachPart() {
        assertEquals(GameVersion(1, 8, 8), GameVersion.parse("1.8.8-R0.1-SNAPSHOT"))
        assertEquals(GameVersion(26, 3), GameVersion.parse("26.3.build.27-alpha"))
        assertEquals(GameVersion(1, 21), GameVersion.parse("1.21-R0.1"))
        assertEquals(GameVersion(0, 0), GameVersion.parse("unknown"))
        assertTrue(GameVersion(1, 16, 5) < GameVersion(1, 21))
        assertTrue(GameVersion(1, 9) > GameVersion(1, 8, 8))
        assertTrue(GameVersion(26, 1) > GameVersion(1, 21, 11))
        assertEquals("1.21.11", GameVersion(1, 21, 11).toString())
        assertEquals("26.3", GameVersion(26, 3).toString())
    }
}
