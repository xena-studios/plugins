package co.xenastudios.core.jvm.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.config.YamlReader
import co.xenastudios.core.common.config.YamlSyntaxError
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Compose
import it.krzeminski.snakeyaml.engine.kmp.exceptions.MarkedYamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.nodes.MappingNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Node
import it.krzeminski.snakeyaml.engine.kmp.nodes.ScalarNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.SequenceNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Tag

/**
 * Reads config files on the JVM with snakeyaml-engine-kmp, the YAML 1.2 parser kaml is built
 * on, into the config system's [YamlNode]s. kaml itself is Java 11 bytecode, which the core's
 * Java 8 floor can't load, and its serialization layer isn't used (DECISIONS.md).
 *
 * Anchors and aliases are allowed, up to 100 aliases, since owners may use them and Bukkit's
 * own YAML reads them. A key twice in one section is an error, as YAML 1.2 says, and so is
 * nesting deeper than the parser can follow (thousands of `[`).
 */
public object JvmYaml : YamlReader {

    private const val BOM = "﻿"

    // Deeper than any config; stops a recursive alias (`a: &a [*a]`) from looping.
    private const val MAX_DEPTH = 64

    // Made by the first read, which is off the server thread: the platform names JvmYaml as it
    // starts, and the parser's settings load its schema and resolver classes.
    private val settings by lazy { LoadSettings(maxAliasesForCollections = 100) }

    private class Failure(message: String, val line: Int, val column: Int = 0) : Exception(message)

    override fun read(text: String): Outcome<YamlNode, YamlSyntaxError> = try {
        val root = Compose(settings).compose(text.removePrefix(BOM))
        // A file of nothing but comments or blank lines has no document: it's empty.
        Outcome.Ok(if (root == null) YamlNode.Null(1) else convert(root, 0))
    } catch (e: MarkedYamlEngineException) {
        val mark = e.problemMark ?: e.contextMark
        Outcome.Err(YamlSyntaxError(listOfNotNull(e.context, e.problem).joinToString(", "), (mark?.line ?: -1) + 1, (mark?.column ?: -1) + 1))
    } catch (e: Failure) {
        Outcome.Err(YamlSyntaxError(e.message ?: "invalid", e.line, e.column))
    } catch (e: RuntimeException) {
        // The parser's other exceptions (too many aliases, too long): still "not valid YAML".
        Outcome.Err(YamlSyntaxError(e.message ?: e.toString(), 0))
    } catch (e: StackOverflowError) {
        // The parser recurses once per level of nesting and has no limit of its own, so ten
        // thousand `{a: ` overflowed the stack (found by fuzzing, core/core-fuzz). The stack has
        // unwound by here, and nothing outside the parser was changed.
        Outcome.Err(YamlSyntaxError("nested too deep to read", 0))
    }

    private fun line(node: Node): Int = (node.startMark?.line ?: -1) + 1

    private fun convert(node: Node, depth: Int): YamlNode {
        if (depth > MAX_DEPTH) throw Failure("nested more than $MAX_DEPTH deep (a recursive alias?)", line(node))
        return when (node) {
            is ScalarNode -> if (node.tag == Tag.NULL) YamlNode.Null(line(node)) else YamlNode.Scalar(node.value, line(node))
            is SequenceNode -> YamlNode.Sequence(node.value.map { convert(it, depth + 1) }, line(node))
            is MappingNode -> {
                val entries = ArrayList<YamlNode.Mapping.Entry>(node.value.size)
                val seen = HashSet<String>()
                for (tuple in node.value) {
                    val keyNode = tuple.keyNode as? ScalarNode
                        ?: throw Failure("a key should be a single value, not a list or a section", line(tuple.keyNode))
                    val key = keyNode.value
                    if (!seen.add(key)) throw Failure("duplicate key \"$key\"", line(keyNode), (keyNode.startMark?.column ?: -1) + 1)
                    entries += YamlNode.Mapping.Entry(key, convert(tuple.valueNode, depth + 1), line(keyNode))
                }
                YamlNode.Mapping(entries, line(node))
            }
            else -> throw Failure("an anchor that isn't resolved", line(node))
        }
    }
}
