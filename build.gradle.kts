// Checks across the whole repo. Each module's own build is in its folder.
plugins {
    base
}

// Code copied from one module into another belongs in the core instead (issue #48).
// PMD's copy-paste detector reads every module's main code; a block it finds in two
// different modules fails the build, and one repeated inside a single module is only
// reported. So is one found only in plugins' APIs (<plugin>/api): an API can't use the
// core, by design, so the boilerplate every Bukkit event needs repeats there. The
// sandbox is scaffolding for CI, so it's left out.
val cpd: Configuration by configurations.creating

dependencies {
    cpd(libs.pmd.cli)
    cpd(libs.pmd.java)
}

val cpdSources = files(subprojects
    .filter { it.path != ":sandbox" && !it.path.startsWith(":sandbox:") }
    .map { it.layout.projectDirectory.dir("src/main/java") }
    .filter { it.asFile.isDirectory })
val cpdReport = layout.buildDirectory.file("reports/cpd/cpd.xml")

val cpdRun = tasks.register<JavaExec>("cpdRun") {
    description = "Finds code repeated within and across modules."
    classpath = cpd
    mainClass = "net.sourceforge.pmd.cli.PmdCli"
    inputs.files(cpdSources).withPropertyName("sources")
    outputs.file(cpdReport)
    // About five lines of code. Smaller matches are mostly boilerplate.
    args("cpd", "--minimum-tokens", "75", "--language", "java", "--format", "xml",
        "--no-fail-on-violation", "--report-file", cpdReport.get().asFile.path)
    args(cpdSources.files.flatMap { listOf("--dir", it.path) })
}

val cpdCheck = tasks.register("cpdCheck") {
    group = "verification"
    description = "Fails if the same code appears in two modules."
    dependsOn(cpdRun)
    val report = cpdReport.map { it.asFile }
    val root = rootDir
    inputs.file(report)
    doLast {
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.get())
        val duplications = xml.getElementsByTagName("duplication")
        val across = mutableListOf<String>()
        for (i in 0 until duplications.length) {
            val duplication = duplications.item(i) as org.w3c.dom.Element
            val files = duplication.getElementsByTagName("file")
            val places = (0 until files.length).map { j ->
                val file = files.item(j) as org.w3c.dom.Element
                java.io.File(file.getAttribute("path")).relativeTo(root).path + ":" + file.getAttribute("line")
            }
            val modules = places.map { it.substringBefore('/') }.toSet()
            val line = "${duplication.getAttribute("lines")} lines in ${places.joinToString(", ")}"
            when {
                modules.size <= 1 -> logger.lifecycle("Repeated within a module: $line")
                places.all { "/api/" in it } -> logger.lifecycle("Repeated across APIs, which can't share the core: $line")
                else -> across += line
            }
        }
        if (across.isNotEmpty()) {
            throw GradleException("The same code is in more than one module; move it to the core (issue #48):\n  " +
                across.joinToString("\n  "))
        }
    }
}

tasks.check {
    dependsOn(cpdCheck)
}
