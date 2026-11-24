package co.xenastudios.core.jvm.text

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageDrawer
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.jvm.i18n.ShopMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdventureRendererTest {

    private fun draw(markup: String, vararg placeholders: Pair<String, Placeholder>) =
        AdventureRenderer.draw(Markup.parse(markup).render(*placeholders))

    @Test
    fun namedColoursStayNamedAndOthersAreRgb() {
        assertEquals(Component.text().content("").style(Style.style().color(NamedTextColor.RED).build()).append(Component.text("Hi")).build(), draw("<red>Hi"))
        val rgb = draw("<#B48CFF>Hi")
        assertEquals(TextColor.color(0xB48CFF), rgb.style().color())
        assertTrue(rgb.style().color() !is NamedTextColor)
    }

    @Test
    fun decorationsAreOnOffOrInherited() {
        val drawn = draw("<bold>a<!bold>b</bold>")
        val outer = drawn.style()
        assertEquals(TextDecoration.State.TRUE, outer.decoration(TextDecoration.BOLD))
        assertEquals(TextDecoration.State.NOT_SET, outer.decoration(TextDecoration.ITALIC))
        val inner = drawn.children()[1]
        assertEquals(TextDecoration.State.FALSE, inner.style().decoration(TextDecoration.BOLD))
    }

    @Test
    fun clicksAndHoversCarryOver() {
        val drawn = draw("<click:run_command:'/home <name>'><hover:show_text:'<red>Go'>x", "name" to Placeholder.text("a"))
        val clicked = generateSequence(drawn) { it.children().firstOrNull() }.first { it.clickEvent() != null }
        assertEquals(ClickEvent.runCommand("/home a"), clicked.clickEvent())
        val hovered = generateSequence(drawn) { it.children().firstOrNull() }.first { it.hoverEvent() != null }
        val hover = hovered.hoverEvent()!!
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.action())
        assertEquals("Go", plain(hover.value() as Component))
        assertEquals(ClickEvent.copyToClipboard("c"), draw("<click:copy_to_clipboard:c>x").clickEvent())
    }

    private fun plain(component: Component): String = buildString {
        if (component is net.kyori.adventure.text.TextComponent) append(component.content())
        for (child in component.children()) append(plain(child))
    }

    @Test
    fun aMessageWithoutPlaceholdersIsDrawnOnceAndKept() {
        val drawer = MessageDrawer(Translations.of(MessageCatalog.of(CoreMessages, ShopMessages), emptyMap()), NoLocale, AdventureRenderer)
        val first = assertNotNull(drawer.draw(null, ShopMessages.empty))
        assertSame(first, drawer.draw(null, ShopMessages.empty))
    }

    private object NoLocale : LanguageSource {
        override fun clientLocale(player: PlayerRef): String? = null
    }
}

class BungeeRendererTest {

    private fun draw(markup: String, renderer: BungeeRenderer = BungeeRenderer()): Array<BaseComponent> =
        renderer.draw(Markup.parse(markup).render())

    @Test
    fun theTreeCarriesOverWithItsStyles() {
        val drawn = draw("<red>Hi <bold>there</bold>")
        assertEquals(1, drawn.size)
        assertEquals("Hi there", BaseComponent.toPlainText(*drawn))
        val root = drawn[0]
        assertEquals(ChatColor.RED, root.colorRaw)
        assertEquals(null, root.isBoldRaw)
        assertEquals(true, root.extra[1].isBoldRaw)
        assertEquals(null, root.extra[1].colorRaw, "inherited, not repeated")
    }

    @Test
    fun hexColoursAreTheNearestNamedOneUnlessTheServerTakesRgb() {
        val old = draw("<#FF66FF>x")
        assertEquals(ChatColor.LIGHT_PURPLE, old[0].colorRaw)
        val modern = BungeeRenderer(hex = true).color(co.xenastudios.core.common.text.TextColor(0xB48CFF))
        assertEquals(ChatColor.of("#b48cff"), modern)
        assertEquals(ChatColor.RED, BungeeRenderer(hex = true).color(co.xenastudios.core.common.text.TextColor(0xFF5555)))
    }

    @Test
    fun clicksAndHoversCarryOverAndCopyFallsBackBefore115() {
        val drawn = draw("<click:run_command:'/x'><hover:show_text:'tip'>go")
        val clicked = flatten(drawn[0]).first { it.clickEvent != null }
        assertEquals(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, clicked.clickEvent.action)
        assertEquals("/x", clicked.clickEvent.value)
        val hovered = flatten(drawn[0]).first { it.hoverEvent != null }
        @Suppress("DEPRECATION")
        assertEquals("tip", BaseComponent.toPlainText(*hovered.hoverEvent.value))
        val copy = flatten(draw("<click:copy_to_clipboard:c>x")[0]).first { it.clickEvent != null }
        assertEquals(net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND, copy.clickEvent.action)
        val modern = flatten(draw("<click:copy_to_clipboard:c>x", BungeeRenderer(copyToClipboard = true))[0]).first { it.clickEvent != null }
        assertEquals(net.md_5.bungee.api.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, modern.clickEvent.action)
    }

    private fun flatten(component: BaseComponent): List<BaseComponent> = listOf(component) + (component.extra ?: emptyList()).flatMap { flatten(it) }

    /**
     * Spigot 1.8.8's bungeecord-chat isn't in any public repository, so the renderer compiles
     * against 1.16's. This keeps it to what 1.8.8 has: every member of the chat API its class
     * files reach must be in this list, taken from Spigot 1.8.8's `bungeecord-chat-1.8-SNAPSHOT`
     * (BuildTools' local Maven repository), apart from the two the platform switches on for
     * newer servers.
     */
    @Test
    fun theRendererOnlyReachesWhatSpigot188Has() {
        val used = sortedSetOf<String>()
        for (type in listOf(BungeeRenderer::class.java, Class.forName(BungeeRenderer::class.java.name + "\$Companion"))) {
            val node = ClassNode()
            val path = "/" + type.name.replace('.', '/') + ".class"
            ClassReader(type.getResourceAsStream(path)!!.use { it.readBytes() }).accept(node, 0)
            for (method in node.methods) {
                for (insn in method.instructions) {
                    when (insn) {
                        is MethodInsnNode -> if (insn.owner.startsWith(BUNGEE)) used += "${insn.owner}.${insn.name}${insn.desc}"
                        is FieldInsnNode -> if (insn.owner.startsWith(BUNGEE)) used += "${insn.owner}.${insn.name}"
                        is TypeInsnNode -> if (insn.desc.startsWith(BUNGEE) && insn.opcode == Opcodes.NEW) used += "new ${insn.desc}"
                    }
                }
            }
        }
        val beyond = used - SPIGOT_188 - SWITCHED
        assertEquals(emptySet(), beyond, "the renderer reaches chat API that Spigot 1.8.8 doesn't have")
        assertTrue(used.containsAll(SWITCHED), "the list of switched members is stale: $used")
    }

    private companion object {
        const val BUNGEE = "net/md_5/bungee/"
        const val CHAT = "net/md_5/bungee/api/chat"
        const val COLOR = "net/md_5/bungee/api/ChatColor"

        // From `javap` on bungeecord-chat 1.8-SNAPSHOT, as Spigot 1.8.8 ships it.
        val SPIGOT_188: Set<String> = buildSet {
            for (name in listOf(
                "BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY",
                "DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE",
            )) add("$COLOR.$name")
            add("new $CHAT/TextComponent")
            add("$CHAT/TextComponent.<init>(Ljava/lang/String;)V")
            for (setter in listOf("setBold", "setItalic", "setUnderlined", "setStrikethrough", "setObfuscated")) {
                add("$CHAT/BaseComponent.$setter(Ljava/lang/Boolean;)V")
                add("$CHAT/TextComponent.$setter(Ljava/lang/Boolean;)V")
            }
            for (owner in listOf("BaseComponent", "TextComponent")) {
                add("$CHAT/$owner.setColor(L$COLOR;)V")
                add("$CHAT/$owner.setClickEvent(L$CHAT/ClickEvent;)V")
                add("$CHAT/$owner.setHoverEvent(L$CHAT/HoverEvent;)V")
                add("$CHAT/$owner.addExtra(L$CHAT/BaseComponent;)V")
            }
            add("new $CHAT/ClickEvent")
            add("$CHAT/ClickEvent.<init>(L$CHAT/ClickEvent\$Action;Ljava/lang/String;)V")
            for (action in listOf("OPEN_URL", "RUN_COMMAND", "SUGGEST_COMMAND")) add("$CHAT/ClickEvent\$Action.$action")
            add("new $CHAT/HoverEvent")
            add("$CHAT/HoverEvent.<init>(L$CHAT/HoverEvent\$Action;[L$CHAT/BaseComponent;)V")
            add("$CHAT/HoverEvent\$Action.SHOW_TEXT")
        }

        // Reached only when the platform says the server has them.
        val SWITCHED: Set<String> = setOf(
            "$COLOR.of(Ljava/lang/String;)L$COLOR;",
            "$CHAT/ClickEvent\$Action.COPY_TO_CLIPBOARD",
        )
    }
}

class LegacyFallbackTest {
    @Test
    fun theLegacyStringMatchesWhatBungeeWouldWrite() {
        val markup = Markup.parse("<gray>Page <highlight>1</highlight> of <gold><bold>3", mapOf("highlight" to co.xenastudios.core.common.text.MarkupTag.Styling(co.xenastudios.core.common.text.Style(color = co.xenastudios.core.common.text.TextColor(0x55FF55)))))
        val component = markup.render()
        val legacy = co.xenastudios.core.common.text.LegacyRenderer().draw(component)
        val bungee = BaseComponent.toLegacyText(*BungeeRenderer().draw(component))
        assertEquals(TextComponent.toPlainText(*TextComponent.fromLegacyText(legacy)), TextComponent.toPlainText(*TextComponent.fromLegacyText(bungee)))
        assertEquals("§7Page §a1§7 of §6§l3", legacy)
    }
}
