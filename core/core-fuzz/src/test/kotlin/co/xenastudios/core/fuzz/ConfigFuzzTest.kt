package co.xenastudios.core.fuzz

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.jvm.config.JvmYaml
import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The config files: the YAML reader on the JVM (core-jvm's `JvmYaml`) and the loader in
 * core-common (`Configs`) that reads, carries forward and rewrites what it read. Whatever is
 * in the file, they say what's wrong instead of throwing (a config that can't be read keeps
 * the last good one), and a file they write reads back as written.
 */
class ConfigFuzzTest {

    /** Any text, read as YAML: a document or a syntax error, never an exception. */
    @FuzzTest
    fun readYaml(data: FuzzedDataProvider) {
        JvmYaml.read(data.consumeRemainingAsString())
    }

    /**
     * Any text, loaded as a config with every kind of setting and a migration. When the
     * loader writes the file back, that file loads again with nothing more to write.
     */
    @FuzzTest
    fun loadConfig(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        val loaded = when (val outcome = Configs.load(FuzzConfig, text, JvmYaml)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return
        }
        // Every setting has a value, whatever the file said (the schema lists them all).
        FuzzConfig.keys.forEach { loaded.config[it] }
        val written = loaded.write ?: return
        when (val again = Configs.load(FuzzConfig, written, JvmYaml)) {
            is Outcome.Ok -> assertNull(again.value.write, "a file the loader wrote changed again on the next load:\n$written")
            is Outcome.Err -> fail("a file the loader wrote can't be read (${again.error}):\n$written")
        }
        assertTrue(written.isNotEmpty())
    }
}

/** A config like a plugin's, at config version 2, with every kind of setting. */
object FuzzConfig : ConfigSchema(2, "A config for fuzzing.") {
    val name: ConfigKey<String> = string("name", "xFuzz", "A string.")
    val count: ConfigKey<Int> = int("count", 3, "An int.", min = 0, max = 100)
    val big: ConfigKey<Long> = long("big", 1L)
    val ratio: ConfigKey<Double> = double("ratio", 2.5, min = 0.0)
    val on: ConfigKey<Boolean> = boolean("on", true)
    val wait: ConfigKey<Duration> = duration("wait", 90.seconds, "A duration.")
    val mode: ConfigKey<Mode> = enum("mode", Mode.FAST, Mode.entries)
    val words: ConfigKey<List<String>> = stringList("words", listOf("a", "b"))
    val numbers: ConfigKey<List<Int>> = list("numbers", ConfigType.INT, listOf(1, 2))
    val section: Section = Section(this)


    enum class Mode { FAST, SLOW }

    class Section(parent: ConfigGroup) : ConfigSection(parent, "messages", "A section.") {
        val welcome: ConfigKey<Markup> = markup("welcome", "<green>Hi, <player>!", formerDefaults = listOf("<gray>Hi."))
        val limit: ConfigKey<Int> = int("limit", 5)
    }

    init {
        migrate(1) {
            move("old-count", "count")
            move("welcome", "messages.welcome")
            remove("gone")
        }
    }
}
