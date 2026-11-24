package co.xenastudios.m0.yaml

import co.xenastudios.m0.Durations
import co.xenastudios.m0.Markup
import co.xenastudios.m0.Parsed
import co.xenastudios.m0.SpikeConfig
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class YamlTest {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    private val file = """
        # A config as owners write it
        config-version: 1
        language: auto
        storage:
          type: mysql
          prefix: "xb_"
        homes:
          limit: 5
          teleport-delay: 5s
        messages:
          welcome: "<gold>Hi <bold>{player}</bold>!"
          bye: '<red>Bye'
        unknown-future-key: [1, 2, 3]
    """.trimIndent()

    @Test fun decodesAConfigIntoTheCommonDataClass() {
        val c = yaml.decodeFromString(SpikeConfig.serializer(), file)
        assertEquals(SpikeConfig.StorageType.MYSQL, c.storage.type)
        assertEquals("xb_", c.storage.prefix)
        assertEquals(5, c.homes.limit)
        assertEquals("en", c.defaultLanguage)
        assertEquals(5.seconds, assertIs<Parsed.Ok<kotlin.time.Duration>>(Durations.parse(c.homes.teleportDelay)).value)
        assertEquals("Hi {player}!", Markup.parse(c.messages.getValue("welcome")).plain())
    }

    @Test fun encodesAndReadsBack() {
        val text = yaml.encodeToString(SpikeConfig.serializer(), SpikeConfig())
        assertTrue("default-language: \"en\"" in text, text)
        assertEquals(SpikeConfig(), yaml.decodeFromString(SpikeConfig.serializer(), text))
    }

    @Test fun reportsTheLineOfABadValue() {
        val e = assertFailsWith<YamlException> {
            yaml.decodeFromString(SpikeConfig.serializer(), "homes:\n  limit: three\n")
        }
        assertEquals(2, e.line)
    }
}
