// A plugin's public API, in <plugin>/api: the interfaces, records, enums and events
// other plugins compile against. The plugin ships it inside its own jar, unrelocated
// (see xena.plugin), and it's published to Maven Central as
// co.xenastudios.<plugin>:<plugin>-api, the way NeoSkript publishes
// co.xenastudios.neoskript:neoskript-api.
//
// It depends on nothing but paper-api, so it can't expose the core: the core is
// relocated into each plugin and isn't part of any API. Rules for what goes in an
// API are in issue #42.
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
    id("xena.java")
    id("com.vanniktech.maven.publish")
}

val pluginProject = checkNotNull(project.parent) { "An API module lives in its plugin's folder, as <plugin>/api." }
val artifact = "${pluginProject.name}-api"
// The plugin's own name, such as xUtilities. The parent is configured first, so it's set.
val pluginName = pluginProject.extensions.getByType<PaperPluginYaml>().name.get()

// Every API project is named "api", and Gradle tells projects apart by group and name,
// so each plugin's API has its own group. Otherwise a project using two plugins' APIs
// (xPlots with xUtilities' and xRewind's) silently gets only one.
group = "co.xenastudios.${pluginProject.name}"
version = pluginProject.version
base.archivesName = artifact

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        // Every public type and member is documented, or the build fails.
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
        addBooleanOption("quiet", true)
        windowTitle = "$pluginName API ${project.version}"
        docTitle = "$pluginName API ${project.version}"
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }
}

// The same setup as NeoSkript's neoskript-api, with its sources and javadoc. Signing and
// the Portal's credentials come from Gradle properties or ORG_GRADLE_PROJECT_* variables
// (signingInMemoryKey, signingInMemoryKeyPassword, mavenCentralUsername,
// mavenCentralPassword); without a key nothing is signed, so local builds and CI don't
// need them. A release tag publishes the plugin's API (release.yml); to try an API from
// another project first, use publishToMavenLocal.
mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    publishToMavenCentral()
    // An unset CI secret comes through as an empty string: that's no key either.
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) {
        signAllPublications()
    }
    coordinates(group.toString(), artifact, version.toString())
    pom {
        name = "$pluginName API"
        // Read late: each API sets its description after this convention is applied.
        description = provider { project.description }
        inceptionYear = "2026"
        url = "https://docs.xenastudios.co/${pluginProject.name}/developer-api/"
        licenses {
            license {
                name = "GPL-3.0-only"
                url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "xena-studios"
                name = "Xena Studios"
                url = "https://xenastudios.co"
            }
        }
        organization {
            name = "Xena Studios"
            url = "https://xenastudios.co"
        }
        scm {
            url = "https://github.com/xena-studios/plugins"
            connection = "scm:git:https://github.com/xena-studios/plugins.git"
            developerConnection = "scm:git:ssh://git@github.com/xena-studios/plugins.git"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/xena-studios/plugins/issues"
        }
    }
}
