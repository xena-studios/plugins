package co.xenastudios.core.bukkit

import co.xenastudios.core.common.text.ClickEvent
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BukkitTextTest {

    private val spigot = setOf("org.spigotmc.SpigotConfig", "net.md_5.bungee.api.chat.BaseComponent", "org.bukkit.entity.Player\$Spigot")
    private val gold = Component("Hello", Style(color = TextColor.NAMED.getValue("gold")))
    private val link = Component("Docs", Style(click = ClickEvent(ClickEvent.Action.OPEN_URL, "https://plugins.xenastudios.co")))

    private fun server(version: String, classes: Set<String> = spigot) = BukkitServer.detect({ it in classes }, version, "CraftBukkit", adventure = false)

    private fun console(inbox: Inbox): CommandSender = fake(
        "CONSOLE",
        mapOf("getName" to { _ -> "CONSOLE" }, "sendMessage" to { args -> inbox.received += args[0]!! }),
    )

    @Test
    fun eachServerGetsItsOwnText() {
        assertEquals("BaseComponent", BukkitText.pick(server("1.8.8")).name)
        assertEquals("legacy text", BukkitText.pick(server("1.8.8", emptySet())).name)
        assertEquals("Adventure", BukkitText.pick(BukkitServer.detect({ false }, "1.21.11", "Paper", adventure = true)).name)
    }

    @Test
    fun spigotPlayersGetComponentsAndTheConsoleAString() {
        val text = BukkitText.pick(server("1.8.8"))
        val players = Inbox()
        val consoles = Inbox()
        text.send(fakePlayer("Steve", players), gold)
        text.send(console(consoles), gold)
        val sent = assertIs<TextComponent>(players.received.single())
        assertEquals("Hello", sent.toPlainText())
        assertEquals("§6Hello", consoles.received.single())
    }

    @Test
    fun from121PlainMessagesGoAsStringsAndClickableOnesAsComponents() {
        val renderer = BukkitText.pick(server("1.21.11")).renderer
        val plain = renderer.draw(gold) as SpigotMessage
        assertNull(plain.components)
        assertEquals("§6Hello", plain.legacy)
        assertNotNull((renderer.draw(Component("See ", children = listOf(link))) as SpigotMessage).components)

        // Before 1.21 a component costs less than a string to send, so every message is one.
        assertNotNull((BukkitText.pick(server("1.20.6")).renderer.draw(gold) as SpigotMessage).components)

        val players = Inbox()
        BukkitText.pick(server("1.21.11")).send(fakePlayer("Alex", players), gold)
        assertEquals("§6Hello", players.received.single())
    }

    @Test
    fun hexColoursFrom116AndTheNearestNamedOneBefore() {
        val purple = Component("x", Style(color = TextColor(0x7C5CFF)))
        assertEquals("§x§7§c§5§c§f§fx", (BukkitText.pick(server("1.16.5")).renderer.draw(purple) as SpigotMessage).legacy)
        assertEquals("§9x", (BukkitText.pick(server("1.8.8")).renderer.draw(purple) as SpigotMessage).legacy)
        assertEquals("§9x", BukkitText.pick(server("1.8.8", emptySet())).renderer.draw(purple))
    }

    @Test
    fun aMessageWithSeveralRootsIsSentWhole() {
        val players = Inbox()
        val parts: Array<BaseComponent> = arrayOf(TextComponent("a"), TextComponent("b"))
        SpigotRenderer.send(fakePlayer("Steve", players), SpigotMessage(parts, "ab"))
        assertEquals(listOf<Any>(listOf(parts[0], parts[1])), players.received)
    }

}
