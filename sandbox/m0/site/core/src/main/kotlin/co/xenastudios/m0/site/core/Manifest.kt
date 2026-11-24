@file:OptIn(ExperimentalSerializationApi::class)

package co.xenastudios.m0.site.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The manifest: what the docs site reads. Plain data, written by Dump.kt from a plugin's
// declarations and its build. Every class and property carries a @Doc, which becomes the
// JSON Schema's description, so the schema is generated from this file too (Schema.kt).
// Bump FORMAT_VERSION on any change the site can't read without changing too.

const val FORMAT_VERSION = 1

/** A description in the JSON Schema. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Doc(val text: String)

@Serializable
@Doc("Everything the docs need about one plugin, generated from its code and build. Don't edit: run ./gradlew updateXenaManifest.")
data class Manifest(
    @SerialName("\$schema") @Doc("The JSON Schema this file follows.")
    val schema: String = "../manifest.schema.json",
    @Doc("The manifest format. The site refuses a version it doesn't know.")
    val formatVersion: Int = FORMAT_VERSION,
    val plugin: PluginInfo,
    @Doc("Where the plugin runs, from its build.")
    val platforms: List<PlatformSupport>,
    @Doc("Every top-level command, with its subcommands. The core adds reload and debug under /<id>.")
    val commands: List<CommandDoc>,
    @Doc("Every permission, with its default and children.")
    val permissions: List<PermissionDoc>,
    @Doc("config.yml, top-level keys in file order.")
    val config: List<ConfigNodeDoc>,
    val messages: MessagesDoc,
    val placeholders: List<PlaceholderDoc>,
    @Doc("The API's events.")
    val events: List<EventDoc>,
)

@Serializable
@Doc("The plugin itself.")
data class PluginInfo(
    @Doc("Lowercase id: the folder, the docs path and the permission prefix.") val id: String,
    @Doc("Display name, such as xBans.") val name: String,
    @Doc("The version this manifest was built from.") val version: String,
    val description: String,
)

@Serializable
enum class PlatformKind { @SerialName("server") SERVER, @SerialName("proxy") PROXY, @SerialName("library") LIBRARY }

@Serializable
@Doc("release: shipped in this version. planned: on the roadmap for a later minor.")
enum class SupportStatus { @SerialName("release") RELEASE, @SerialName("planned") PLANNED }

@Serializable
@Doc("One platform the plugin runs on, or will.")
data class PlatformSupport(
    @Doc("Stable id, such as paper.") val id: String,
    @Doc("Display name, such as Paper.") val name: String,
    val kind: PlatformKind,
    @Doc("The oldest Minecraft (or proxy or API) version supported.") val minVersion: String,
    @Doc("The newest, or latest.") val maxVersion: String,
    @Doc("The Java version the server needs at least; absent where Java doesn't apply.") val java: Int? = null,
    val status: SupportStatus,
    @Doc("The plugin version that added it.") val since: String? = null,
)

@Serializable
@Doc("A command or subcommand.")
data class CommandDoc(
    @Doc("The full command without the slash, such as xdemo give.") val path: String,
    @Doc("The usage line, such as /xdemo give <player> <amount>.") val usage: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    @Doc("The permission it needs; absent when everyone may use it.") val permission: String? = null,
    @Doc("Only players can run it, not the console.") val playerOnly: Boolean = false,
    val arguments: List<ArgumentDoc> = emptyList(),
    @Doc("Set by the core: every plugin has it (ROADMAP.md, Admin commands).") val builtIn: Boolean = false,
    val subcommands: List<CommandDoc> = emptyList(),
)

@Serializable
@Doc("One argument of a command.")
data class ArgumentDoc(
    val name: String,
    @Doc("player, integer, number, text, choice or duration.") val type: String,
    val required: Boolean,
    val description: String,
    @Doc("For a choice: what it accepts.") val choices: List<String> = emptyList(),
    @Doc("For a number: the smallest it accepts.") val min: Double? = null,
    @Doc("For a number: the largest it accepts.") val max: Double? = null,
)

@Serializable
enum class PermissionDefault {
    @SerialName("op") OP, @SerialName("everyone") EVERYONE, @SerialName("nobody") NOBODY, @SerialName("not-op") NOT_OP,
}

@Serializable
@Doc("A permission node.")
data class PermissionDoc(
    val node: String,
    val description: String,
    @SerialName("default") @Doc("Who has it without a permissions plugin.") val defaultValue: PermissionDefault,
    @Doc("Nodes it grants (true) or takes away (false).") val children: Map<String, Boolean> = emptyMap(),
    @Doc("The commands that need it, by path.") val commands: List<String> = emptyList(),
)

@Serializable
enum class ConfigType {
    @SerialName("section") SECTION, @SerialName("string") STRING, @SerialName("integer") INTEGER,
    @SerialName("number") NUMBER, @SerialName("boolean") BOOLEAN, @SerialName("list") LIST,
    @SerialName("duration") DURATION, @SerialName("choice") CHOICE,
}

@Serializable
@Doc("A key in config.yml: a section with children, or a setting.")
data class ConfigNodeDoc(
    @Doc("Dotted path from the top, such as storage.type.") val path: String,
    val type: ConfigType,
    @Doc("The comment written above it in the default file, one line per entry.") val comment: List<String> = emptyList(),
    @SerialName("default") @Doc("The default value, for a setting.") val defaultValue: JsonElement? = null,
    @Doc("For a choice: what it accepts.") val choices: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    @Doc("Whether /<plugin> reload applies it; false means a restart.") val reloadable: Boolean = true,
    val children: List<ConfigNodeDoc> = emptyList(),
)

@Serializable
@Doc("Messages and their translations.")
data class MessagesDoc(
    @Doc("The English file every other language falls back to.") val file: String,
    val keys: List<MessageDoc>,
    @Doc("Every language shipped, and how much of it is translated.") val languages: List<LanguageDoc>,
)

@Serializable
@Doc("One translation key.")
data class MessageDoc(
    val key: String,
    @SerialName("default") @Doc("The English text, in the core's markup.") val defaultText: String,
    @Doc("The tags it fills in, such as player.") val placeholders: List<String> = emptyList(),
    val description: String,
)

@Serializable
@Doc("One shipped language.")
data class LanguageDoc(
    @Doc("BCP 47 tag, such as pt-BR.") val tag: String,
    val translated: Int,
    val total: Int,
    @Doc("Keys still in English.") val missing: List<String> = emptyList(),
)

@Serializable
@Doc("A placeholder other plugins can show.")
data class PlaceholderDoc(
    @Doc("Without the plugin's prefix, such as coins.") val id: String,
    val description: String,
    val example: String,
    @Doc("How to write it in each placeholder plugin.") val forms: Map<String, String>,
)

@Serializable
@Doc("An API event.")
data class EventDoc(
    @Doc("The class developers listen for.") val className: String,
    val description: String,
    val cancellable: Boolean,
    val fields: List<FieldDoc> = emptyList(),
    val since: String,
)

@Serializable
data class FieldDoc(val name: String, val type: String, val description: String)
