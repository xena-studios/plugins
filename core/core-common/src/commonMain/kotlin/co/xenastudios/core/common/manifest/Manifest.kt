package co.xenastudios.core.common.manifest

import co.xenastudios.core.common.command.Confirmation
import co.xenastudios.core.common.command.Cooldown
import co.xenastudios.core.common.command.CommandNode
import co.xenastudios.core.common.config.ConfigDescription
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.manifest.JsonType.ArrayOf
import co.xenastudios.core.common.manifest.JsonType.Bool
import co.xenastudios.core.common.manifest.JsonType.Integer
import co.xenastudios.core.common.manifest.JsonType.MapOf
import co.xenastudios.core.common.manifest.JsonType.OneOf
import co.xenastudios.core.common.manifest.JsonType.Ref
import co.xenastudios.core.common.manifest.JsonType.Text
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.time.Durations

/**
 * A plugin's reference manifest: everything its docs pages show, as JSON, written from its
 * [PluginReference] and the platforms its build names, with a JSON Schema the docs site
 * validates it against (ROADMAP.md, Site). The build writes both (`./gradlew xenaManifest`)
 * and fails when the committed copy in `docs/src/generated/<plugin>/` is stale.
 */
public object Manifest {
    /**
     * The manifest's format. The schema pins it, so a site that hasn't learned a new format
     * fails its build rather than show half a page: bump it on any change the site's
     * components must follow.
     */
    public const val FORMAT_VERSION: Int = 1

    /** The schema's file name, beside the plugins' folders in `docs/src/generated/`. */
    public const val SCHEMA_FILE: String = "manifest.schema.json"

    /** The manifest of [reference] at [version], running on [platforms], as JSON. */
    public fun write(reference: PluginReference, version: String, platforms: List<PlatformInfo>): String =
        ManifestFormat.manifest(reference, version, platforms).write()

    /** The JSON Schema (2020-12) every manifest follows. */
    public fun schema(): String = ManifestFormat.schema().write()
}

/** The manifest's shapes, and how a reference becomes one. */
internal object ManifestFormat {
    private val manifest = Shape("Manifest", "Everything the docs show about one plugin, generated from its code and build. Don't edit it: run ./gradlew updateXenaManifest.")
    private val plugin = Shape("Plugin", "The plugin itself.")
    private val platform = Shape("Platform", "One platform the plugin runs on, or will.")
    private val command = Shape("Command", "A command or subcommand.")
    private val argument = Shape("Argument", "One argument of a command.")
    private val permission = Shape("Permission", "A permission node.")
    private val config = Shape("Config", "config.yml, from the plugin's config schema.")
    private val configEntry = Shape("ConfigEntry", "A section of config.yml, or a setting.")
    private val placeholder = Shape("Placeholder", "A placeholder other plugins can show.")
    private val message = Shape("Message", "A message, in English, which each language's file translates.")
    private val event = Shape("Event", "An event of the plugin's API.")

    private val strings = ArrayOf(Text)

    init {
        manifest.apply {
            required("\$schema", Text, "The JSON Schema this file follows.")
            required("formatVersion", JsonType.Const(Manifest.FORMAT_VERSION), "The manifest's format. The site refuses one it doesn't know.")
            required("plugin", Ref { plugin }, "The plugin itself.")
            required("platforms", ArrayOf(Ref { platform }), "Where it runs, from its build.")
            required("commands", ArrayOf(Ref { command }), "Its commands, each with its subcommands.")
            required("permissions", ArrayOf(Ref { permission }), "Its permissions, in the order it declares them.")
            optional("config", Ref { config }, "Its config.yml; absent if it has none.")
            required("placeholders", ArrayOf(Ref { placeholder }), "Its placeholders.")
            required("messages", ArrayOf(Ref { message }), "Its messages.")
            required("events", ArrayOf(Ref { event }), "Its API's events.")
        }
        plugin.apply {
            required("id", Text, "Lower-case id: its folder, its docs path and its permissions' prefix.")
            required("name", Text, "Its name, such as xHomes.")
            required("version", Text, "The version this manifest was written from.")
            required("description", Text, "One line on what it does.")
        }
        platform.apply {
            required("id", Text, "Stable id, such as paper.")
            required("name", Text, "As people read it, such as Paper.")
            required("kind", OneOf(PlatformInfo.Kind.entries.map { it.id }), "What runs it.")
            required("minVersion", Text, "The oldest Minecraft (or proxy or API) version supported.")
            required("maxVersion", Text, "The newest, or latest.")
            optional("java", Integer, "The Java the server needs at least; absent where Java doesn't apply.")
            required("status", OneOf(PlatformInfo.Status.entries.map { it.id }), "release: out in this version. planned: on the roadmap.")
            optional("since", Text, "The plugin version that added it.")
        }
        command.apply {
            required("path", Text, "The words from the root, without the slash, such as xhomes set.")
            required("usage", Text, "How to type it, such as /xhomes set <name>.")
            required("aliases", strings, "Other names it answers to.")
            required("description", Text, "What it does.")
            optional("permission", Text, "The permission it needs on top of its parents'; absent if theirs are enough.")
            required("playerOnly", Bool, "Only players can run it, not the console.")
            required("arguments", ArrayOf(Ref { argument }), "What it takes after its name, in order.")
            required("executable", Bool, "Typing it does something; false for a command that only holds subcommands.")
            optional("cooldown", Text, "How long a player waits between uses, as the configs write durations.")
            optional("cooldownBypass", Text, "The permission that skips the cooldown.")
            required("confirm", Bool, "It runs only when typed twice.")
            required("builtIn", Bool, "The core gives it to every plugin (ROADMAP.md, Admin commands).")
            required("subcommands", ArrayOf(Ref { command }), "The commands under it.")
        }
        argument.apply {
            required("name", Text, "Its name in the usage.")
            required("type", Text, "What it takes: word, text, integer, number, boolean, choice, duration, player, player name, or a plugin's own.")
            required("required", Bool, "It must be given.")
            required("greedy", Bool, "It takes the rest of the line, spaces and all.")
            required("description", Text, "What it's for.")
            required("choices", strings, "The words it takes, if it takes only some.")
            optional("min", JsonType.Number, "The smallest number it takes (for a duration, in seconds).")
            optional("max", JsonType.Number, "The largest number it takes (for a duration, in seconds).")
        }
        permission.apply {
            required("node", Text, "The node, such as xhomes.set.")
            required("description", Text, "What having it allows.")
            required("default", OneOf(PermissionDefault.entries.map { it.id }), "Who has it without a permissions plugin.")
            required("children", MapOf(Bool), "Nodes it grants (true) or takes away (false).")
            required("commands", strings, "The commands that need it, by path.")
            required("builtIn", Bool, "The core declares it for every plugin.")
        }
        config.apply {
            required("version", Integer, "The config version, which the file's own version: key carries.")
            required("header", strings, "The lines at the top of the file.")
            required("entries", ArrayOf(Ref { configEntry }), "The top-level sections and settings, in file order.")
        }
        configEntry.apply {
            required("path", Text, "Keys joined with dots, such as storage.type.")
            required("type", Text, "section, or the setting's type: string, integer, number, boolean, duration, markup, enum, list<...>.")
            required("comment", strings, "The comment above it in the default file, a line each.")
            optional("default", JsonType.AnyValue, "A setting's default value.")
            optional("defaultText", Text, "A setting's default, as the file writes it.")
            required("choices", strings, "The values a setting takes, if it takes only some.")
            optional("min", JsonType.Number, "The smallest number a setting takes.")
            optional("max", JsonType.Number, "The largest number a setting takes.")
            required("entries", ArrayOf(Ref { configEntry }), "A section's sections and settings.")
        }
        placeholder.apply {
            required("name", Text, "Its name within the plugin, such as balance.")
            required("description", Text, "What it shows.")
            required("example", Text, "What it might show.")
            required("needsPlayer", Bool, "It has a value only for a player.")
            required("forms", MapOf(Text), "How each placeholder plugin writes it, by plugin.")
        }
        message.apply {
            required("key", Text, "Its key in every language's file, such as homes.set.")
            required("english", Text, "Its English text, in the core's markup.")
            required("comment", strings, "The comment above it in each file.")
            required("placeholders", strings, "The tags it fills in when sent, such as name for <name>.")
        }
        event.apply {
            required("key", Text, "Its event type's key, such as xmoney:balance_changed.")
            required("className", Text, "The class developers listen for.")
            required("description", Text, "When it fires.")
            required("cancellable", Bool, "Listeners can cancel it.")
            optional("since", Text, "The API version that added it.")
        }
    }

    fun manifest(reference: PluginReference, version: String, platforms: List<PlatformInfo>): Json.Obj {
        val nodes = reference.commands.nodes
        return manifest.of(
            "\$schema" to Json.of("../${Manifest.SCHEMA_FILE}"),
            "formatVersion" to Json.of(Manifest.FORMAT_VERSION),
            "plugin" to plugin.of(
                "id" to Json.of(reference.id),
                "name" to Json.of(reference.name),
                "version" to Json.of(version),
                "description" to Json.of(reference.description),
            ),
            "platforms" to Json.Arr(platforms.map(::platform)),
            "commands" to Json.Arr(reference.commands.roots.map(::command)),
            "permissions" to Json.Arr(
                reference.permissions.flatMap { it.permissions }.map { p ->
                    permission.of(
                        "node" to Json.of(p.node),
                        "description" to Json.of(p.description),
                        "default" to Json.of(p.default.id),
                        "children" to Json.Obj(p.children.map { (node, on) -> node to Json.of(on) }),
                        "commands" to Json.strings(nodes.filter { it.permission === p }.map { it.path.joinToString(" ") }),
                        "builtIn" to Json.of(p.builtIn),
                    )
                },
            ),
            "config" to reference.config?.describe()?.let(::config),
            "placeholders" to Json.Arr(
                reference.placeholders?.let { set ->
                    set.placeholders.map { p ->
                        placeholder.of(
                            "name" to Json.of(p.name),
                            "description" to Json.of(p.description),
                            "example" to Json.of(p.example),
                            "needsPlayer" to Json.of(p.needsPlayer),
                            "forms" to Json.Obj(set.forms(p).map { (k, v) -> k to Json.of(v) }),
                        )
                    }
                }.orEmpty(),
            ),
            "messages" to Json.Arr(
                reference.messages.map { m ->
                    message.of(
                        "key" to Json.of(m.key),
                        "english" to Json.of(m.english),
                        "comment" to Json.strings(m.comment),
                        "placeholders" to Json.strings(m.placeholders),
                    )
                },
            ),
            "events" to Json.Arr(
                reference.events.map { e ->
                    event.of(
                        "key" to Json.of(e.key),
                        "className" to Json.of(e.className),
                        "description" to Json.of(e.description),
                        "cancellable" to Json.of(e.cancellable),
                        "since" to Json.of(e.since),
                    )
                },
            ),
        )
    }

    private fun platform(p: PlatformInfo) = platform.of(
        "id" to Json.of(p.id),
        "name" to Json.of(p.name),
        "kind" to Json.of(p.kind.id),
        "minVersion" to Json.of(p.minVersion),
        "maxVersion" to Json.of(p.maxVersion),
        "java" to p.java?.let { Json.of(it) },
        "status" to Json.of(p.status.id),
        "since" to Json.of(p.since),
    )

    private fun command(node: CommandNode): Json.Obj {
        val cooldown = node.hooks.filterIsInstance<Cooldown>().firstOrNull()
        return command.of(
            "path" to Json.of(node.path.joinToString(" ")),
            "usage" to Json.of(node.usage),
            "aliases" to Json.strings(node.aliases),
            "description" to Json.of(node.description),
            "permission" to Json.of(node.permission?.node),
            "playerOnly" to Json.of(node.playerOnly),
            "arguments" to Json.Arr(
                node.arguments.map { a ->
                    argument.of(
                        "name" to Json.of(a.name),
                        "type" to Json.of(a.type.name),
                        "required" to Json.of(a.required),
                        "greedy" to Json.of(a.type.greedy),
                        "description" to Json.of(a.description),
                        "choices" to Json.strings(a.type.choices),
                        "min" to Json.of(a.type.min),
                        "max" to Json.of(a.type.max),
                    )
                },
            ),
            "executable" to Json.of(node.executable),
            "cooldown" to Json.of(cooldown?.duration?.let(Durations::write)),
            "cooldownBypass" to Json.of(cooldown?.bypass?.node),
            "confirm" to Json.of(node.hooks.any { it is Confirmation }),
            "builtIn" to Json.of(node.builtIn),
            "subcommands" to Json.Arr(node.children.map(::command)),
        )
    }

    private fun config(description: ConfigDescription) = config.of(
        "version" to Json.of(description.version),
        "header" to Json.strings(description.header),
        "entries" to Json.Arr(description.entries.map(::configEntry)),
    )

    private fun configEntry(entry: ConfigDescription.Entry): Json.Obj = when (entry) {
        is ConfigDescription.Section -> configEntry.of(
            "path" to Json.of(entry.path),
            "type" to Json.of("section"),
            "comment" to Json.strings(entry.comment),
            "choices" to Json.strings(emptyList()),
            "entries" to Json.Arr(entry.entries.map(::configEntry)),
        )
        is ConfigDescription.Setting -> configEntry.of(
            "path" to Json.of(entry.path),
            "type" to Json.of(entry.type),
            "comment" to Json.strings(entry.comment),
            "default" to yaml(entry.default, entry.type),
            "defaultText" to Json.of(entry.defaultText),
            "choices" to Json.strings(entry.choices),
            "min" to Json.of(entry.min?.takeIf { it.isFinite() && it > -MAX_LIMIT }),
            "max" to Json.of(entry.max?.takeIf { it.isFinite() && it < MAX_LIMIT }),
            "entries" to Json.Arr(emptyList()),
        )
    }

    // An int or long setting without a limit has the type's own as its min and max: no limit at all, to a reader.
    private const val MAX_LIMIT = 2.0e9

    /** A default as JSON: numbers and booleans as such for settings of those types, anything else as YAML has it. */
    private fun yaml(node: YamlNode, type: String): Json = when (node) {
        is YamlNode.Scalar -> when (type) {
            "integer", "number" -> node.content.toDoubleOrNull()?.let { Json.Num(it) } ?: Json.of(node.content)
            "boolean" -> node.content.toBooleanStrictOrNull()?.let { Json.of(it) } ?: Json.of(node.content)
            else -> Json.of(node.content)
        }
        is YamlNode.Sequence -> Json.Arr(node.items.map { yaml(it, type.removePrefix("list<").removeSuffix(">")) })
        is YamlNode.Mapping -> Json.Obj(node.entries.map { it.key to yaml(it.value, "") })
        is YamlNode.Null -> Json.Null
    }

    /** The JSON Schema: each shape a `$defs` entry, every field described, nothing else allowed. */
    fun schema(): Json.Obj {
        val defs = LinkedHashMap<String, Json>()
        fun visit(shape: Shape) {
            if (shape.name in defs) return
            defs[shape.name] = Json.Null
            defs[shape.name] = Json.obj(
                "type" to Json.of("object"),
                "description" to Json.of(shape.doc),
                "properties" to Json.Obj(
                    shape.fields.map { field ->
                        field.name to Json.Obj(field.type.schema().members + ("description" to Json.of(field.doc)))
                    },
                ),
                "required" to Json.strings(shape.fields.filter { it.required }.map { it.name }),
                "additionalProperties" to Json.of(false),
            )
            for (field in shape.fields) refs(field.type).forEach(::visit)
        }
        visit(manifest)
        return Json.obj(
            "\$schema" to Json.of("https://json-schema.org/draft/2020-12/schema"),
            "title" to Json.of("Xena plugin reference manifest, format ${Manifest.FORMAT_VERSION}"),
            "\$ref" to Json.of("#/\$defs/${manifest.name}"),
            "\$defs" to Json.Obj(defs.toList()),
        )
    }

    private fun refs(type: JsonType): List<Shape> = when (type) {
        is Ref -> listOf(type.shape)
        is ArrayOf -> refs(type.item)
        is MapOf -> refs(type.value)
        else -> emptyList()
    }
}
