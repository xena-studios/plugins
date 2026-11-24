package co.xenastudios.core.jvm.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigLoad
import co.xenastudios.core.common.config.ConfigProblem
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.config.YamlSyntaxError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** The JVM's YAML reader reads what the config system writes, and what owners write by hand. */
class JvmYamlTest {

    private fun read(text: String): YamlNode = assertIs<Outcome.Ok<YamlNode>>(JvmYaml.read(text)).value

    private fun load(text: String): ConfigLoad = assertIs<Outcome.Ok<ConfigLoad>>(Configs.load(ShopConfig, text, JvmYaml)).value

    @Test
    fun theDefaultFileReadsBackUnchanged() {
        val load = load(Configs.defaultText(ShopConfig))
        assertEquals(emptyList(), load.problems)
        assertNull(load.write)
        assertEquals(ShopConfig.defaults, load.config)
    }

    @Test
    fun readsWhatOwnersWrite() {
        val config = load(
            """
            # my shop
            config-version: 2
            name: 'Bob''s shop'   # a comment after a value
            mode: Quiet
            timing: &t
              open-for: 1h 30m
            words:
              - one
              - "two"
            tagged: !!str 5
            """.trimIndent(),
        ).config
        assertEquals("Bob's shop", config[ShopConfig.name])
        assertEquals(Mode.QUIET, config[ShopConfig.mode])
        assertEquals(90.minutes, config[ShopConfig.timing.openFor])
        assertEquals(listOf("one", "two"), config[ShopConfig.words])
    }

    @Test
    fun linesAreTheFilesLines() {
        val load = load("config-version: 2\nname: shop\ntiming:\n  open-for: soon\n  max: lots\nunknown: 1\n")
        assertEquals(listOf(4, 5, 6), load.problems.map { it.line })
        assertEquals(listOf(ConfigProblem.Kind.BAD_VALUE, ConfigProblem.Kind.BAD_VALUE, ConfigProblem.Kind.UNKNOWN_KEY), load.problems.map { it.kind })
        assertEquals("timing.open-for (\"soon\") should be a length of time like 30s, 5m or 1h30m; using the default.", load.problems[0].message)
    }

    @Test
    fun nodesKeepTheirShapeAndOrder() {
        val root = assertIs<YamlNode.Mapping>(read("b: 1\na:\n  - x\n  - [y, z]\nc:\nd: {e: f}\n"))
        assertEquals(listOf("b", "a", "c", "d"), root.entries.map { it.key })
        assertEquals(
            YamlNode.Sequence(listOf(YamlNode.Scalar("x"), YamlNode.Sequence(listOf(YamlNode.Scalar("y"), YamlNode.Scalar("z"))))),
            root["a"],
        )
        assertEquals(YamlNode.Null(), root["c"])
        assertEquals(6, root.entry("d")?.line)
    }

    @Test
    fun anEmptyFileIsEmpty() {
        assertIs<YamlNode.Null>(read(""))
        assertIs<YamlNode.Null>(read("# only a comment\n\n"))
        assertIs<YamlNode.Mapping>(read("﻿a: 1\n"))
    }

    @Test
    fun aSyntaxErrorSaysWhere() {
        val error = assertIs<Outcome.Err<YamlSyntaxError>>(JvmYaml.read("a: 1\nb: [1, 2\nc: 3\n")).error
        assertEquals(true, error.line >= 2, error.toString())
        val duplicate = assertIs<Outcome.Err<YamlSyntaxError>>(JvmYaml.read("a: 1\nb:\n  c: 1\n  c: 2\n")).error
        assertEquals("duplicate key \"c\"", duplicate.message)
        assertEquals(4, duplicate.line)
    }

    @Test
    fun aTooManyAliasesFileIsRefused() {
        val bomb = buildString {
            append("a: &a [x, x]\n")
            for (i in 1..120) append("k$i: *a\n")
        }
        assertIs<Outcome.Err<YamlSyntaxError>>(JvmYaml.read(bomb))
        assertIs<Outcome.Err<YamlSyntaxError>>(JvmYaml.read("a: &a [*a]\n"))
    }

    // Found by fuzzing (core/core-fuzz): the parser recurses once per level, with no limit.
    @Test
    fun aFileNestedTooDeepIsRefusedNotThrown() {
        val deep = assertIs<Outcome.Err<YamlSyntaxError>>(JvmYaml.read("{a: ".repeat(100_000) + "1" + "}".repeat(100_000))).error
        assertEquals("nested too deep to read", deep.message)
        assertIs<Outcome.Err<YamlSyntaxError>>(JvmYaml.read("[".repeat(100_000)))
        assertIs<Outcome.Err<*>>(Configs.load(ShopConfig, "a: " + "[".repeat(100_000), JvmYaml))
    }
}
