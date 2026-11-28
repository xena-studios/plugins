package co.xenastudios.xstats.fabric

import net.minecraft.server.level.ServerPlayer
import java.lang.reflect.Method
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier

/**
 * Placeholder stats (`papi:%player:statistic jump%` in config.yml's `stats`) through Patbox's Text
 * Placeholder API, when it's installed: a placeholder's id, and its argument after a space, filled
 * for the player at each save, on the server thread, as PlaceholderAPI's are on Bukkit.
 *
 * Through its API by name, as [TextPlaceholders]: 2.x (Minecraft 1.20.1 to 1.21.11) has
 * `Placeholders.parsePlaceholder(id, argument, PlaceholderContext)` and a result's `string()`, 3.x
 * (26.1 on) `parseServerPlaceholder(id, argument, ServerPlaceholderContext)` and `component()`.
 */
internal class PlaceholderStats private constructor(
    private val parse: Method,
    private val context: Method,
    private val valid: Method,
    private val text: Method,
) {
    /** [placeholder] filled for [player], or `null` if it isn't one Text Placeholder API knows. */
    fun fill(player: ServerPlayer, placeholder: String): String? {
        val inner = placeholder.removePrefix("%").removeSuffix("%").trim()
        val id = Identifier.tryParse(inner.substringBefore(' ')) ?: return null
        val argument = inner.substringAfter(' ', "").trim().ifEmpty { null }
        val result = parse.invoke(null, id, argument, context.invoke(null, player)) ?: return null
        if (valid.invoke(result) != true) return null
        return when (val value = text.invoke(result)) {
            is net.minecraft.network.chat.Component -> value.string
            else -> value?.toString()
        }
    }

    companion object {
        /** The API this server has, or `null` without Text Placeholder API (or one this doesn't know). */
        fun find(): PlaceholderStats? = try {
            val api = Class.forName("eu.pb4.placeholders.api.Placeholders")
            val result = Class.forName("eu.pb4.placeholders.api.PlaceholderResult")
            val server = api.methods.firstOrNull { it.name == "parseServerPlaceholder" && it.parameterCount == 3 }
            if (server != null) {
                val context = Class.forName("eu.pb4.placeholders.api.ServerPlaceholderContext").getMethod("of", ServerPlayer::class.java)
                PlaceholderStats(server, context, result.getMethod("isValid"), result.getMethod("component"))
            } else {
                val parse = api.methods.first { it.name == "parsePlaceholder" && it.parameterCount == 3 }
                val context = Class.forName("eu.pb4.placeholders.api.PlaceholderContext").getMethod("of", ServerPlayer::class.java)
                PlaceholderStats(parse, context, result.getMethod("isValid"), result.getMethod("string"))
            }
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: NoSuchElementException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}
