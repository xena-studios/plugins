package co.xenastudios.core.common.manifest

import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.Reloader
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.HomesConfig
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.placeholder.placeholders
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

object HomeMessages : MessageBundle() {
    val set: MessageKey = message("homes.set", "Home <name> set.", "When a home is set.", placeholders = listOf("name"))
}

class HomeSetEvent : CancellableEvent() {
    companion object {
        val TYPE: EventType<HomeSetEvent> = EventType.of("xhomes:home_set")
    }
}

class HomeGoneEvent : Event {
    companion object {
        val TYPE: EventType<HomeGoneEvent> = EventType.of("xhomes:home_gone")
    }
}

class ManifestTest {

    private object Perms : PermissionSet("xhomes") {
        val set: Permission = permission("set", "Set homes.", PermissionDefault.EVERYONE)
        val fast: Permission = permission("fast", "No cooldown.", PermissionDefault.NOBODY)
        val stray: Permission = permission("stray", "Not used by any command.")
    }

    private val admin = AdminCommands("xhomes", "xHomes", FakePlatform(), Reloader { error("not in a manifest") })
    private val tree = commands {
        root("xhomes", "Your homes.", aliases = listOf("homes")) {
            literal("set", "Sets a home.", permission = Perms.set) {
                playerOnly()
                argument("name", ArgumentTypes.choice("base", "farm"), "Its name.")
                optional("radius", ArgumentTypes.integer(min = 1, max = 16), "How far.")
                cooldown(30.seconds, bypass = Perms.fast)
                executes { }
            }
            mount(admin)
        }
    }
    private val reference = PluginReference(
        "xhomes", "xHomes", "Homes for players.", tree, listOf(Perms, admin.permissions),
        config = HomesConfig,
        placeholders = placeholders("xhomes") { player("count", "How many homes you have.", "3") { "3" } },
        catalog = MessageCatalog.of(HomeMessages),
        events = listOf(EventInfo.of(HomeSetEvent.TYPE, "A home is set."), EventInfo.of(HomeGoneEvent.TYPE, "A home was deleted.", since = "2.1.0")),
    )
    private val paper = PlatformInfo("paper", "Paper", PlatformInfo.Kind.SERVER, "1.8.8", "latest", 8, PlatformInfo.Status.RELEASE, "2.0.0")
    private val manifest = ManifestFormat.manifest(reference, "2.0.0", listOf(paper))

    private fun Json.obj(key: String) = (this as Json.Obj)[key] as Json.Obj
    private fun Json.arr(key: String) = ((this as Json.Obj)[key] as Json.Arr).items
    private fun Json.str(key: String) = ((this as Json.Obj)[key] as Json.Str?)?.value

    @Test
    fun theManifestDescribesWhatRuns() {
        assertEquals("../manifest.schema.json", manifest.str("\$schema"))
        assertEquals("xHomes", manifest.obj("plugin").str("name"))
        val root = manifest.arr("commands").single()
        assertEquals("/xhomes <set|reload|debug>", root.str("usage"))
        val (set, reload) = root.arr("subcommands")
        assertEquals("/xhomes set <name> [radius]", set.str("usage"))
        assertEquals("30s", set.str("cooldown"))
        assertEquals("xhomes.fast", set.str("cooldownBypass"))
        assertEquals(listOf("base", "farm"), set.arr("arguments")[0].arr("choices").map { (it as Json.Str).value })
        assertEquals(16.0, (set.arr("arguments")[1] as Json.Obj)["max"].let { (it as Json.Num).value })
        assertEquals("xhomes.admin.reload", reload.str("permission"))
        assertEquals(true, ((reload as Json.Obj)["builtIn"] as Json.Bool).value)

        val permissions = manifest.arr("permissions").map { it.str("node") }
        assertEquals(listOf("xhomes.set", "xhomes.fast", "xhomes.stray", "xhomes.admin", "xhomes.admin.reload", "xhomes.admin.debug"), permissions)
        val setPermission = manifest.arr("permissions")[0]
        assertEquals(listOf("xhomes set"), setPermission.arr("commands").map { (it as Json.Str).value })
        assertEquals("everyone", setPermission.str("default"))

        val forms = manifest.arr("placeholders").single().obj("forms")
        assertEquals("%xhomes_count%", forms.str("PlaceholderAPI"))
        assertEquals("<xhomes_count>", forms.str("MiniPlaceholders"))
        assertEquals("%xhomes:count%", forms.str("Text Placeholder API"))
        val message = manifest.arr("messages").single()
        assertEquals("homes.set", message.str("key"))
        assertEquals("Home <name> set.", message.str("english"))
        assertEquals(listOf("name"), message.arr("placeholders").map { (it as Json.Str).value })
        val (setEvent, gone) = manifest.arr("events")
        assertEquals("xhomes:home_set", setEvent.str("key"))
        assertTrue(setEvent.str("className")!!.endsWith("HomeSetEvent"), "read from the event's class")
        assertEquals(true, ((setEvent as Json.Obj)["cancellable"] as Json.Bool).value, "a pre-event, which listeners can cancel")
        assertEquals(false, ((gone as Json.Obj)["cancellable"] as Json.Bool).value)
        assertEquals("2.1.0", gone.str("since"))
        assertEquals("1.8.8", manifest.arr("platforms").single().str("minVersion"))
    }

    @Test
    fun theConfigIsTheSchemasOwnDescription() {
        val config = manifest.obj("config")
        assertEquals(2.0, (config["version"] as Json.Num).value)
        val entries = config.arr("entries")
        assertEquals(listOf("language", "storage", "homes", "messages"), entries.map { it.str("path") })
        val homes = entries[2]
        assertEquals("section", homes.str("type"))
        val limit = homes.arr("entries")[0] as Json.Obj
        assertEquals("homes.limit", limit.str("path"))
        assertEquals(3.0, (limit["default"] as Json.Num).value)
        assertEquals(100.0, (limit["max"] as Json.Num).value)
        val delay = homes.arr("entries")[1] as Json.Obj
        assertEquals("\"1m30s\"", delay.str("defaultText"))
        val worlds = homes.arr("entries")[4] as Json.Obj
        assertEquals(listOf("world_nether"), (worlds["default"] as Json.Arr).items.map { (it as Json.Str).value })
        assertEquals(listOf("sqlite", "mysql", "flat-file"), entries[1].arr("choices").map { (it as Json.Str).value })
    }

    @Test
    fun theSameReferenceWritesTheSameText() {
        val text = Manifest.write(reference, "2.0.0", listOf(paper))
        assertEquals(text, Manifest.write(reference, "2.0.0", listOf(paper)))
        assertTrue(text.startsWith("{\n  \"\$schema\": \"../manifest.schema.json\",\n  \"formatVersion\": 1,\n  \"plugin\": {\n    \"id\": \"xhomes\","))
        assertTrue(text.endsWith("}\n"))
    }

    @Test
    fun theSchemaComesFromTheSameShapes() {
        val schema = ManifestFormat.schema()
        assertEquals("#/\$defs/Manifest", schema.str("\$ref"))
        val defs = schema.obj("\$defs")
        assertEquals(
            listOf("Manifest", "Plugin", "Platform", "Command", "Argument", "Permission", "Config", "ConfigEntry", "Placeholder", "Message", "Event"),
            defs.members.map { it.first },
        )
        val command = defs.obj("Command")
        assertEquals(false, (command["additionalProperties"] as Json.Bool).value)
        assertTrue("permission" !in command.arr("required").map { (it as Json.Str).value })
        assertTrue("usage" in command.arr("required").map { (it as Json.Str).value })
        assertEquals(1.0, (defs.obj("Manifest").obj("properties").obj("formatVersion")["const"] as Json.Num).value)
        assertEquals(
            listOf("op", "everyone", "nobody", "not-op"),
            defs.obj("Permission").obj("properties").obj("default").arr("enum").map { (it as Json.Str).value },
        )
        assertTrue(Manifest.schema().contains("\"\$ref\": \"#/\$defs/Command\""))
    }

    @Test
    fun aShapeRefusesWhatItDoesntDeclare() {
        val shape = Shape("Thing", "A thing.").apply {
            required("name", JsonType.Text, "")
            optional("size", JsonType.Integer, "")
        }
        assertEquals(listOf("name"), shape.of("name" to Json.of("a"), "size" to Json.of(null as Double?)).members.map { it.first })
        assertFailsWith<IllegalArgumentException> { shape.of("size" to Json.of(1)) }
        assertFailsWith<IllegalArgumentException> { shape.of("name" to Json.of("a"), "colour" to Json.of("red")) }
        assertFailsWith<IllegalArgumentException> { shape.of("name" to Json.of(1)) }
        assertFailsWith<IllegalArgumentException> { shape.of("name" to Json.of("a"), "size" to Json.of(1.5)) }
    }

    @Test
    fun jsonIsWrittenTheSameEverywhere() {
        val json = Json.obj(
            "text" to Json.of("a \"quote\", a \\ and a\nnew line\u0001"),
            "whole" to Json.of(3.0),
            "part" to Json.of(2.5),
            "none" to Json.Null,
            "empty" to Json.Arr(emptyList()),
            "nested" to Json.obj("list" to Json.strings(listOf("x", "y")), "map" to Json.obj()),
        )
        assertEquals(
            """
            {
              "text": "a \"quote\", a \\ and a\nnew line\u0001",
              "whole": 3,
              "part": 2.5,
              "none": null,
              "empty": [],
              "nested": {
                "list": [
                  "x",
                  "y"
                ],
                "map": {}
              }
            }

            """.trimIndent(),
            json.write(),
        )
    }

    @Test
    fun aReferenceDeclaresEveryPermissionItUses() {
        val e = assertFailsWith<IllegalArgumentException> { PluginReference("xhomes", "xHomes", "", tree, listOf(Perms)) }
        assertEquals("/xhomes reload uses xhomes.admin.reload, which xhomes doesn't declare", e.message)
        assertFailsWith<IllegalArgumentException> { PluginReference("other", "Other", "", tree, listOf(Perms, admin.permissions)) }
    }

    @Test
    fun platformsComeFromTheBuildAsLines() {
        assertEquals(paper, PlatformInfo.parse(paper.line()))
        val pumpkin = PlatformInfo.parse("pumpkin;Pumpkin;server;latest;latest;;planned;")
        assertEquals(null, pumpkin.java)
        assertEquals(null, pumpkin.since)
        assertFailsWith<IllegalArgumentException> { PlatformInfo.parse("paper;Paper;server;1.8.8;latest;8;shipped;") }
    }
}
