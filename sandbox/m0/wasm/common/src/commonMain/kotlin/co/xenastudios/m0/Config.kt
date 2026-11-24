package co.xenastudios.m0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A plugin config as the rewrite would type it: defaults in the class, unknown keys ignored. */
@Serializable
data class SpikeConfig(
    @SerialName("config-version") val configVersion: Int = 1,
    val language: String = "auto",
    @SerialName("default-language") val defaultLanguage: String = "en",
    val storage: Storage = Storage(),
    val homes: Homes = Homes(),
    val messages: Map<String, String> = mapOf("welcome" to "<green>Welcome, <bold>{player}</bold>!"),
) {
    @Serializable
    data class Storage(val type: StorageType = StorageType.SQLITE, val prefix: String = "xena_")

    @Serializable
    enum class StorageType {
        @SerialName("sqlite") SQLITE,
        @SerialName("mysql") MYSQL,
    }

    @Serializable
    data class Homes(
        val limit: Int = 3,
        /** Kept as text in the file and read with [Durations], so owners write `5s` or `1m`. */
        @SerialName("teleport-delay") val teleportDelay: String = "3s",
    )
}

object Configs {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(text: String): Parsed<SpikeConfig> = try {
        Parsed.Ok(json.decodeFromString(SpikeConfig.serializer(), text))
    } catch (e: SerializationException) {
        Parsed.Err(ParseError(text, 0, e.message ?: "invalid config"))
    } catch (e: IllegalArgumentException) {
        Parsed.Err(ParseError(text, 0, e.message ?: "invalid config"))
    }

    fun encode(config: SpikeConfig): String = json.encodeToString(SpikeConfig.serializer(), config)
}
