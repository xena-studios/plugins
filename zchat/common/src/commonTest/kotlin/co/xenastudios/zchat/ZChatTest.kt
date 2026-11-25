package co.xenastudios.zchat

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeChat
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.zchat.api.MessageKind
import co.xenastudios.zchat.api.ZChat
import co.xenastudios.zchat.api.event.ChatMessageEvent
import co.xenastudios.zchat.api.event.ChatMessageSentEvent
import co.xenastudios.zchat.api.event.FilterMatchEvent
import co.xenastudios.zchat.api.event.MentionEvent
import co.xenastudios.zchat.api.event.PrivateMessageEvent
import co.xenastudios.zchat.config.ZChatConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** zChat's chat and commands, as its adapters run them: fake players on the fake platform, chatting through FakeChat. */
class ZChatTest {
    private val platform = FakePlatform()
    private val server = FakeServer()
    // A bridge per opening, as a platform makes one each time chat handling turns on (a reload too).
    private var chat = FakeChat(platform.log)
    private val bridges = ChatBridges { FakeChat(platform.log).also { fresh -> chat = fresh; joined.forEach { (ref, name) -> fresh.join(ref, name) } } }
    private val joined = LinkedHashMap<co.xenastudios.core.api.PlayerRef, String>()
    private var configText: String? = null
    private val edited = ArrayList<String>()

    private val admin = FakeCommandSource.player("Admin").apply { everything = true }
    // What every player has by default on a real server (the platform gives a permission's default).
    private val everyone = arrayOf("zchat.command.msg", "zchat.command.reply", "zchat.command.togglechat")
    private val steve = FakeCommandSource.player("Steve", *everyone)
    private val alex = FakeCommandSource.player("Alex", *everyone)
    private val console = FakeCommandSource.console()
    private val adminPlayer = server.add(admin)
    private val stevePlayer = server.add(steve)
    private val alexPlayer = server.add(alex)

    private fun config(): Config = configText?.let { (Configs.load(ZChatConfig, it, TestYaml) as Outcome.Ok).value.config } ?: ZChatConfig.defaults
    private fun loaded() = Loaded(config(), Translations.of(ZChatMessages.catalog, emptyMap()))

    private val files = object : Files {
        override fun load(): Loaded = loaded()
        override fun editWords(word: String, add: Boolean): Outcome<Boolean, String> {
            val words = config()[ZChatConfig.filter.words]
            if (add == (word in words)) return Outcome.Ok(false)
            val next = if (add) words + word else words - word
            configText = "filter:\n  words: [${next.joinToString { "\"$it\"" }}]\n"
            edited += word
            return Outcome.Ok(true)
        }
    }

    private val plugin = ZChatPlugin(platform, server, files, server, bridges).also { plugin ->
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded()) }
        for (player in server.players.values) {
            platform.scheduler.spawn(player.ref.uuid, Location(WorldRef.of("world"), 0.0, 64.0, 0.0))
            joined[player.ref] = player.source.name
            chat.join(player.ref, player.source.name)
            plugin.joined(player.ref, player.source.name)
        }
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
    }

    private fun run(source: FakeCommandSource, line: String): CommandResult {
        val result = platform.scheduler.runAs(Where.Global) { plugin.dispatcher.dispatch(source, line) }
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.tick()
        return result
    }

    private fun say(player: FakeServer.Player, text: String): FakeChat.Said = chat.say(player.ref, text).also { platform.scheduler.tick() }

    private fun reload(text: String) {
        configText = text
        run(console, "zchat reload")
        platform.scheduler.tick()
    }

    @Test
    fun chatIsDrawnOnceInThePlayersFormat() {
        val said = say(stevePlayer, "hello")
        assertEquals("Steve: hello", said.seenBy(alexPlayer.ref))
        assertEquals("Steve: hello", said.console?.plain())
        assertEquals(1, said.draws, "one line for everyone")
        steve.permissions += "zchat.group.vip"
        platform.scheduler.advance(5.seconds)
        assertEquals("[VIP] Steve » hello again", say(stevePlayer, "hello again").seenBy(alexPlayer.ref))
        // The highest weight wins.
        steve.permissions += "zchat.group.admin"
        platform.scheduler.advance(5.seconds)
        assertEquals("[Admin] Steve » third", say(stevePlayer, "third").seenBy(alexPlayer.ref))
        assertEquals("admin", plugin.getFormatName(stevePlayer.ref))
    }

    @Test
    fun playersCantInjectFormattingUnlessAllowed() {
        val plain = say(stevePlayer, "<red>hi <click:run_command:'/op Steve'>click</click>")
        assertEquals("Steve: <red>hi <click:run_command:'/op Steve'>click</click>", plain.seenBy(alexPlayer.ref))
        steve.permissions += "zchat.chat.color"
        platform.scheduler.advance(5.seconds)
        val styled = say(stevePlayer, "<red>hi <click:run_command:'/op Steve'>there<newline>you")
        assertEquals("Steve: hi there you", styled.seenBy(alexPlayer.ref))
        val line = styled.lines.getValue(alexPlayer.ref)
        fun clicks(c: co.xenastudios.core.common.text.Component): Boolean = c.style.click != null || c.style.hover != null || c.children.any(::clicks)
        assertFalse(clicks(line), "styles only, never clicks")
    }

    @Test
    fun theFilterCensorsAndCanBlock() {
        val said = say(stevePlayer, "you b4dw0rd1")
        assertEquals("Steve: you ********", said.seenBy(alexPlayer.ref))
        assertEquals("you ********", said.event?.message, "other plugins and the log see the censored text")
        reload("filter:\n  mode: block\n")
        platform.scheduler.advance(5.seconds)
        val blocked = say(stevePlayer, "badword1")
        assertTrue(blocked.cancelled)
        assertEquals("» Your message was blocked by the chat filter.", stevePlayer.messages.last().plain())
        // The bypass skips it.
        steve.permissions += "zchat.bypass.filter"
        assertFalse(say(stevePlayer, "badword1").cancelled)
    }

    @Test
    fun theCooldownAndAntiRepeatCountOnlySentMessages() {
        say(stevePlayer, "first message")
        val tooSoon = say(stevePlayer, "second message")
        assertTrue(tooSoon.cancelled)
        assertEquals("» Wait 3s before chatting again.", stevePlayer.messages.last().plain())
        platform.scheduler.advance(4.seconds)
        assertTrue(say(stevePlayer, "first message!").cancelled, "the same again within 30 s")
        assertEquals("» You just said that.", stevePlayer.messages.last().plain())
        assertFalse(say(stevePlayer, "gg").cancelled, "under three letters is never a repeat")
        platform.scheduler.advance(4.seconds)
        assertFalse(say(stevePlayer, "something else entirely").cancelled)
    }

    @Test
    fun mentionsHighlightAndPingOnlyWhoTheSenderCanSee() {
        val said = say(stevePlayer, "hi alex and @Admin")
        assertEquals("Steve: hi @Alex and @Admin", said.seenBy(alexPlayer.ref))
        assertEquals(1, alexPlayer.sounds.size)
        assertEquals(1, adminPlayer.sounds.size)
        assertTrue(stevePlayer.sounds.isEmpty(), "never yourself")
        // Hidden by the server, or by a vanish plugin through the API: not highlighted, not pinged.
        platform.scheduler.advance(5.seconds)
        plugin.registerHiddenPlayers("xVanish") { _, target -> target == adminPlayer.ref }
        val hidden = say(stevePlayer, "where is admin")
        assertEquals("Steve: where is admin", hidden.seenBy(alexPlayer.ref))
        assertEquals(1, adminPlayer.sounds.size)
        // @everyone, for those allowed.
        platform.scheduler.advance(5.seconds)
        say(adminPlayer, "@everyone listen")
        assertEquals(2, alexPlayer.sounds.size)
        assertEquals(1, stevePlayer.sounds.size)
    }

    @Test
    fun muteChatClearChatAndToggleChat() {
        run(admin, "mutechat")
        assertTrue(plugin.isChatMuted())
        assertEquals("» Chat has been muted by Admin.", stevePlayer.messages.last().plain())
        assertTrue(say(stevePlayer, "hello?").cancelled)
        assertFalse(say(adminPlayer, "i may").cancelled, "operators bypass")
        run(admin, "mc")
        assertFalse(plugin.isChatMuted())
        platform.scheduler.advance(5.seconds)

        run(steve, "togglechat")
        assertEquals("» Chat is now hidden. Run it again to show it.", steve.replies.last())
        val said = say(alexPlayer, "anyone there")
        assertNull(said.seenBy(stevePlayer.ref))
        assertEquals("Alex: anyone there", said.seenBy(alexPlayer.ref), "your own messages still show")
        assertTrue(plugin.hasChatHidden(stevePlayer.ref))
        // Remembered: back after a rejoin.
        plugin.quit(stevePlayer.ref)
        assertFalse(plugin.hasChatHidden(stevePlayer.ref))
        plugin.joined(stevePlayer.ref, "Steve")
        assertTrue(plugin.hasChatHidden(stevePlayer.ref))

        run(admin, "clearchat")
        assertEquals(100, alexPlayer.messages[alexPlayer.messages.size - 2].plain().count { it == '\n' } + 1)
        assertEquals("» Chat was cleared by Admin.", alexPlayer.messages.last().plain())
        assertEquals("» Chat was cleared by Admin.", console.replies.lastOrNull() ?: server.console.last().plain())
        assertEquals(CommandResult.NoPermission("zchat.command.clearchat"), run(steve, "clearchat"))
    }

    @Test
    fun privateMessagesRepliesAndSpies() {
        run(admin, "socialspy")
        run(steve, "msg alex hi there")
        assertEquals("You → Alex: hi there", steve.replies.last())
        assertEquals("Steve → You: hi there", alexPlayer.messages.last().plain())
        assertEquals(1, alexPlayer.sounds.size)
        assertEquals("[Spy] Steve → Alex: hi there", adminPlayer.messages.last().plain())
        run(alex, "r hello back")
        assertEquals("Alex → You: hello back", stevePlayer.messages.last().plain())
        assertEquals("» No player called Nobody is online.", run(steve, "msg Nobody hi").let { steve.replies.last() })
        run(steve, "msg alex what a badword1")
        assertEquals("Steve → You: what a ********", alexPlayer.messages.last().plain())
        // The console can message and be replied to.
        run(console, "tell steve from the console")
        assertEquals("CONSOLE → You: from the console", stevePlayer.messages.last().plain())
        run(steve, "reply yes?")
        assertEquals("Steve → You: yes?", server.console.last().plain())
        assertEquals("» You have nobody to reply to.", run(admin, "r anyone").let { admin.replies.last() })
    }

    @Test
    fun selectorsAreForThoseMinecraftAllows() {
        server.selector = { _, selector -> if (selector == "@a") server.players.keys.toList() else throw IllegalArgumentException() }
        run(steve, "msg @a hi")
        assertEquals("» No player called @a is online.", steve.replies.last(), "a selector is a name without the permission")
        run(admin, "msg @a everyone")
        assertEquals("Admin → You: everyone", stevePlayer.messages.last().plain())
        assertEquals("Admin → You: everyone", alexPlayer.messages.last().plain())
    }

    @Test
    fun theFilterCommandEditsTheConfig() {
        run(admin, "filter add creeper*")
        assertEquals("» Added creeper* to the filter.", admin.replies.last())
        assertEquals(listOf("creeper*"), edited)
        run(admin, "filter test the creepers came")
        assertEquals("» That would show as: the ******** came", admin.replies.last())
        run(admin, "filter add creeper*")
        assertEquals("» creeper* is already in the filter.", admin.replies.last())
        run(admin, "filter add two words")
        assertTrue(admin.replies.last().startsWith("» A filter word is one word"))
        run(admin, "filter remove creeper*")
        assertEquals("» Removed creeper* from the filter.", admin.replies.last())
        run(admin, "filter test the creepers came")
        assertEquals("» That passes the filter.", admin.replies.last())
        run(admin, "filter list")
        assertEquals("» Filtered words (2): badword1, badword2", admin.replies.last())
    }

    @Test
    fun theApiMutesAddsPlaceholdersAndFiresEvents() {
        assertSame(plugin, ZChat.api())
        val provider = co.xenastudios.zchat.api.MuteProvider { player, kind -> if (player == stevePlayer.ref) "<error>Muted for $kind" else null }
        plugin.registerMuteProvider("xBans", provider)
        assertTrue(say(stevePlayer, "let me talk").cancelled)
        assertEquals("Muted for CHAT", stevePlayer.messages.last().plain())
        run(steve, "msg alex psst")
        assertEquals("Muted for PRIVATE_MESSAGE", steve.replies.last())
        plugin.unregisterMuteProvider(provider)

        reload("formatting:\n  default-format: \"<balance> <player>: <message>\"\n")
        plugin.registerPlaceholder("balance", { "<gold>$5" })
        val heard = ArrayList<String>()
        plugin.events.subscribe(ChatMessageEvent.TYPE) { heard += "chat ${it.message} ${it.formatName}" }
        plugin.events.subscribe(ChatMessageSentEvent.TYPE) { heard += "sent ${it.message} ${it.recipients.size}" }
        plugin.events.subscribe(FilterMatchEvent.TYPE) { heard += "filter ${it.matches} ${it.kind}" }
        plugin.events.subscribe(MentionEvent.TYPE) { heard += "mention"; it.isCancelled = true }
        plugin.events.subscribe(PrivateMessageEvent.TYPE) { it.message = it.message.uppercase() }
        assertEquals("$5 Steve: ******** @Alex", say(stevePlayer, "badword1 alex").seenBy(alexPlayer.ref))
        assertEquals(listOf("filter [badword1] CHAT", "chat ******** alex default", "sent ******** alex 3", "mention"), heard)
        assertTrue(alexPlayer.sounds.isEmpty(), "the mention event was cancelled")
        run(steve, "msg alex quiet")
        assertEquals("Steve → You: QUIET", alexPlayer.messages.last().plain())
        assertEquals(MessageKind.CHAT, MessageKind.valueOf("CHAT"))
    }

    @Test
    fun reloadKeepsStateAndAppliesEverything() {
        run(admin, "mutechat")
        reload("mutechat:\n  enabled: false\nclearchat:\n  aliases: [\"wipe\"]\n")
        assertFalse(plugin.isChatMuted(), "turning /mutechat off unmutes chat")
        assertTrue(plugin.dispatcher.tree.roots.none { it.name == "mutechat" })
        assertTrue(plugin.dispatcher.tree.roots.single { it.name == "clearchat" }.aliases.contains("wipe"))
        run(console, "zchat")
        assertTrue(console.replies.last().startsWith("» zChat 4.0.0 on Fake"), console.replies.last())
    }
}
