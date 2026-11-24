plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "Fuzz tests for the rewrite's parsers: the markup and the config files. Tests only; never shipped."

// Jazzer's JUnit fuzz tests (ROADMAP.md, "Robust": parsers get fuzzing). Each @FuzzTest is a
// JUnit test that, in `test`, runs once per input in its committed corpus
// (src/test/resources/<package>/<Class>Inputs/<method>/), crashes found before included, so
// they stay fixed. `fuzz` fuzzes each one for real (JAZZER_FUZZ=1), for -Pfuzz.duration
// (20s unless set: CI runs a few seconds on a pull request and longer on a schedule); a crash
// fails it and is written next to the corpus, to commit with the fix; the inputs it
// finds go to .cifuzz-corpus/ (ignored by git).
dependencies {
    testImplementation(project(":core:core-jvm"))
    testImplementation(libs.jazzer.junit)
}

// One task per fuzz test: Jazzer fuzzes one per JVM.
val targets = mapOf(
    "fuzzMarkup" to "co.xenastudios.core.fuzz.MarkupFuzzTest.parseAndRender",
    "fuzzMarkupEscape" to "co.xenastudios.core.fuzz.MarkupFuzzTest.escapeRoundTrips",
    "fuzzYaml" to "co.xenastudios.core.fuzz.ConfigFuzzTest.readYaml",
    "fuzzConfig" to "co.xenastudios.core.fuzz.ConfigFuzzTest.loadConfig",
)
val duration = providers.gradleProperty("fuzz.duration").orElse("20s")
val testSources = sourceSets.test.get()
val fuzzTasks = targets.map { (name, target) ->
    tasks.register<Test>(name) {
        group = "verification"
        description = "Fuzzes $target."
        testClassesDirs = testSources.output.classesDirs
        classpath = testSources.runtimeClasspath
        filter { includeTestsMatching(target) }
        environment("JAZZER_FUZZ", "1")
        systemProperty("jazzer.max_duration", duration.get())
        // Only our code and the YAML parser are instrumented for coverage.
        systemProperty("jazzer.instrumentation_includes", "co.xenastudios.**:it.krzeminski.**")
        // Never up to date, and never from the cache: each run fuzzes anew.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
tasks.register("fuzz") {
    group = "verification"
    description = "Fuzzes every fuzz test, each for -Pfuzz.duration."
    dependsOn(fuzzTasks)
}
