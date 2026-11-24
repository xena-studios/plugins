@file:JvmName("TranslationCheckMain")

package co.xenastudios.core.jvm.i18n

import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.TranslationCheck
import co.xenastudios.core.common.i18n.TranslationIssue
import co.xenastudios.core.common.manifest.ManifestSource
import co.xenastudios.core.jvm.config.JvmYaml
import java.io.File
import kotlin.system.exitProcess

/**
 * What the build's `checkTranslations` task runs (the `xena.manifest` convention in build-logic),
 * on the JVM with the plugin's classes: [TranslationCheck] on every `lang/<tag>.yml` the plugin
 * ships, against the English messages of its reference's catalog. Prints each issue and exits
 * with 1 if there's any, so a missing key or a broken tag fails the build, not a server
 * (ROADMAP.md, Translations).
 *
 * Arguments: `--source <class>` (the plugin's `ManifestSource` object), `--lang <folder>` for
 * each folder of language files, and `--allow-missing` to let a partial translation pass.
 */
public fun main(args: Array<String>) {
    val issues = check(args.toList())
    if (issues == null) return
    if (issues.isNotEmpty()) {
        System.err.println("${issues.size} translation issue${if (issues.size == 1) "" else "s"}:")
        for (issue in issues) System.err.println("  $issue")
        exitProcess(1)
    }
}

/**
 * The issues [args] name, or `null` if the plugin sends no messages. A file whose name isn't a
 * language tag is an issue too.
 */
internal fun check(args: List<String>): List<TranslationIssue>? {
    val allowMissing = "--allow-missing" in args
    val options = args.filter { it != "--allow-missing" }.chunked(2)
        .onEach { require(it.size == 2) { "${it[0]} needs a value" } }
        .groupBy({ it[0] }, { it[1] })
    val sourceName = options["--source"]?.singleOrNull() ?: throw IllegalArgumentException("--source is needed, once")

    // The one reflective call, at build time only: the object the build names.
    val source = Class.forName(sourceName).getField("INSTANCE").get(null) as? ManifestSource
        ?: throw IllegalArgumentException("$sourceName isn't an object implementing ManifestSource")
    val catalog = source.reference.catalog ?: return null

    val issues = ArrayList<TranslationIssue>()
    val files = options["--lang"].orEmpty().map(::File).filter { it.isDirectory }
        .flatMap { folder -> folder.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".yml") } }
        .sortedBy { it.name }
    for (file in files) {
        val name = file.name.removeSuffix(".yml")
        val tag = LanguageTag.parse(name)
        if (tag == null || tag.toString() != name) {
            issues += TranslationIssue(
                TranslationIssue.Kind.INVALID_FILE, tag ?: LanguageTag.ENGLISH, "",
                "lang/${file.name}: the name should be a language tag, such as de.yml or pt-BR.yml",
            )
            continue
        }
        issues += TranslationCheck.check(catalog, tag, file.readText(), JvmYaml, allowMissing)
    }
    println("Checked ${files.size} translation${if (files.size == 1) "" else "s"} of ${catalog.size} messages: ${issues.size} issue${if (issues.size == 1) "" else "s"}")
    return issues
}
