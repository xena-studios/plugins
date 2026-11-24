package co.xenastudios.m0.site.core

import kotlinx.serialization.json.Json
import java.io.File

// The xenaManifest Gradle task's entry point. Runs on the JVM at build time, against the
// plugin's own classes: no server, no platform. Writes:
//   <out>/<id>/manifest.json    the reference the docs render
//   <out>/<id>/config.yml       the default config, as today's golden
//   <out>/manifest.schema.json  the manifest's JSON Schema

val manifestJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
}

fun manifest(def: PluginDefinition, version: String, platforms: List<PlatformSupport>, languages: List<LanguageDoc>): Manifest {
    fun doc(c: Command): CommandDoc = CommandDoc(
        path = c.path.joinToString(" "),
        usage = c.usage,
        aliases = c.aliases,
        description = c.description,
        permission = c.permission?.node,
        playerOnly = c.playerOnly,
        arguments = c.args.map { a ->
            ArgumentDoc(
                a.name, a.type, a.required, a.description,
                choices = (a as? Arg.ChoiceArg)?.choices ?: emptyList(),
                min = (a as? Arg.IntegerArg)?.min?.toDouble(),
                max = (a as? Arg.IntegerArg)?.max?.toDouble(),
            )
        },
        builtIn = c.builtIn,
        subcommands = c.children.map(::doc),
    )
    val all = def.commands.flatMap { it.flatten() }
    return Manifest(
        plugin = PluginInfo(def.id, def.name, version, def.description),
        platforms = platforms,
        commands = def.commands.map(::doc),
        permissions = def.permissions.map { p ->
            PermissionDoc(p.node, p.description, p.default, p.children, all.filter { it.permission == p }.map { it.path.joinToString(" ") })
        },
        config = def.config.docs(),
        messages = MessagesDoc(
            file = "lang/en.yml",
            keys = def.messages.messages.map { MessageDoc(it.key, it.english, it.placeholders, it.description) },
            languages = languages,
        ),
        placeholders = def.placeholders.map {
            PlaceholderDoc(
                it.id, it.description, it.example,
                linkedMapOf(
                    "PlaceholderAPI" to "%${def.id}_${it.id}%",
                    "MiniPlaceholders" to "<${def.id}_${it.id}>",
                    "Text Placeholder API" to "%${def.id}:${it.id}%",
                ),
            )
        },
        events = def.events.map { EventDoc(it.className, it.description, it.cancellable, it.fields, it.since) },
    )
}

fun parsePlatform(line: String): PlatformSupport {
    val f = line.split(';')
    require(f.size == 8) { "platform: id;name;kind;min;max;java;status;since, got $line" }
    return PlatformSupport(
        id = f[0], name = f[1],
        kind = PlatformKind.valueOf(f[2].uppercase()),
        minVersion = f[3], maxVersion = f[4],
        java = f[5].toIntOrNull(),
        status = SupportStatus.valueOf(f[6].uppercase()),
        since = f[7].ifEmpty { null },
    )
}

fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).groupBy({ it[0] }, { it[1] })
    fun one(k: String) = opts[k]?.single() ?: error("missing $k")
    // The one reflective call, at build time only: the class the build names.
    val provider = Class.forName(one("--definition")).getField("INSTANCE").get(null) as PluginDefinitionProvider
    val def = provider.definition
    val langDir = File(one("--lang"))
    val languages = (langDir.listFiles { f -> f.extension == "yml" && f.name != "en.yml" } ?: emptyArray())
        .sortedBy { it.name }
        .map { checkLanguage(it.nameWithoutExtension, it.readText(), def.messages) }
    val m = manifest(def, one("--version"), opts["--platform"].orEmpty().map(::parsePlatform), languages)

    val out = File(one("--out"))
    File(out, def.id).mkdirs()
    File(out, "${def.id}/manifest.json").writeText(manifestJson.encodeToString(Manifest.serializer(), m) + "\n")
    File(out, "${def.id}/config.yml").writeText(def.config.defaultYaml())
    val schema = jsonSchema(Manifest.serializer().descriptor, "Xena plugin reference manifest, format $FORMAT_VERSION")
    File(out, "manifest.schema.json").writeText(manifestJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), schema) + "\n")
    println("Wrote ${def.id}: ${m.commands.sumOf { 1 + it.subcommands.size }} commands, ${m.permissions.size} permissions, " +
        "${m.messages.keys.size} messages, ${m.placeholders.size} placeholders, ${m.events.size} events, ${m.platforms.size} platforms")
}
