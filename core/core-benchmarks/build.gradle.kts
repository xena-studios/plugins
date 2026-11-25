import co.xenastudios.gradle.BenchmarkBudget

// JMH benchmarks for the core's hot paths (ROADMAP.md, "Fast": hot paths don't allocate what
// they can avoid): markup, drawing messages per language, the event bus, commands and config
// reads, each against a line in budgets.txt. Never shipped.
//
//   ./gradlew :core:core-benchmarks:benchmark          every benchmark, JMH's fuller settings
//   ./gradlew :core:core-benchmarks:checkBenchmarks    a quick run, then fails when one is
//                                                      over its budget (CI's step)
//   ./gradlew :core:core-benchmarks:benchmarkQuick -Pbenchmarks=ChatBenchmark   one class only
//
// Neither is part of `build` or `check`: a run takes minutes and wants a quiet machine.
// `build` compiles the benchmarks and JMH's generated harness, so they can't rot.
//
// JMH runs without a Gradle plugin: its generator reads the compiled benchmark classes (it
// can't read Kotlin sources) and writes the harness, which is compiled and run with JMH's own
// main class. The Gradle plugins for JMH and kotlinx-benchmark do the same, and both call
// APIs Gradle 10 removes.
plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios.core"
description = "JMH benchmarks for the core's hot paths, with budgets. Never shipped."

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-jvm"))
    implementation(project(":core:core-testing"))
    implementation(libs.jmh.core)
    // The servers' own text, which messages are drawn to: compile-only in core-jvm.
    implementation(libs.adventure.api)
    implementation(libs.bungeecord.chat)
}

val jmhGenerator = configurations.dependencyScope("jmhGenerator")
val jmhGeneratorClasspath = configurations.resolvable("jmhGeneratorClasspath") { extendsFrom(jmhGenerator.get()) }
dependencies {
    "jmhGenerator"(libs.jmh.generator.bytecode)
}

val main = sourceSets.main.get()
val generated = layout.buildDirectory.dir("generated/jmh")

// JMH's harness for each benchmark, and its list of them (META-INF/BenchmarkList).
val jmhGenerate = tasks.register<JavaExec>("jmhGenerate") {
    description = "Writes JMH's harness for the benchmark classes."
    val classes = main.output.classesDirs
    inputs.files(classes).withPropertyName("classes").withNormalizer(ClasspathNormalizer::class)
    outputs.dir(generated).withPropertyName("generated")
    classpath(jmhGeneratorClasspath, classes, main.runtimeClasspath)
    mainClass = "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator"
    val out = generated.get().asFile
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(classes.files.first { it.path.contains("kotlin") }.path, out.resolve("java").path, out.resolve("resources").path, "default")
    })
    doFirst { out.deleteRecursively() }
}

val jmh = sourceSets.create("jmh") {
    java.srcDir(jmhGenerate.map { generated.get().dir("java") })
    resources.srcDir(jmhGenerate.map { generated.get().dir("resources") })
    compileClasspath += main.output + main.runtimeClasspath
    runtimeClasspath += main.output + main.runtimeClasspath
}

tasks.named("assemble") { dependsOn(tasks.named(jmh.classesTaskName)) }

/** A JMH run: every benchmark, -prof gc for bytes allocated per call, results as JSON at [report]. */
fun benchmarkRun(name: String, description: String, report: String, vararg settings: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    this.description = description
    classpath(jmh.runtimeClasspath)
    mainClass = "org.openjdk.jmh.Main"
    val file = layout.buildDirectory.file(report)
    outputs.file(file)
    outputs.upToDateWhen { false }
    args(*settings, "-bm", "avgt", "-tu", "ns", "-f", "1", "-prof", "gc", "-rf", "json")
    // -Pbenchmarks=<regex> runs only the benchmarks it matches, such as ChatBenchmark.
    val only = providers.gradleProperty("benchmarks")
    argumentProviders.add(CommandLineArgumentProvider { listOf("-rff", file.get().asFile.path) + listOfNotNull(only.orNull) })
    doFirst { file.get().asFile.parentFile.mkdirs() }
}

benchmarkRun("benchmark", "Runs every benchmark with JMH.", "reports/benchmarks/full/results.json", "-wi", "3", "-w", "1s", "-i", "5", "-r", "1s")
val quick = benchmarkRun("benchmarkQuick", "Runs every benchmark briefly, for the budget check.", "reports/benchmarks/quick/results.json", "-wi", "2", "-w", "1s", "-i", "3", "-r", "1s")

tasks.register<BenchmarkBudget>("checkBenchmarks") {
    group = "verification"
    description = "Runs the quick benchmarks and fails when one is over its line in budgets.txt."
    dependsOn(quick)
    reports.set(layout.buildDirectory.dir("reports/benchmarks/quick"))
    budgets.set(layout.projectDirectory.file("budgets.txt"))
    summary.set(layout.buildDirectory.file("reports/benchmarks/budgets.md"))
}
