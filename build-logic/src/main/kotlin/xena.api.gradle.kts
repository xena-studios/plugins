// A plugin's public API, in <plugin>/api: the interfaces, records, enums and events
// other plugins compile against. The plugin ships it inside its own jar, unrelocated
// (see xena.plugin), and it's published as co.xenastudios:<plugin>-api.
//
// It depends on nothing but paper-api, so it can't expose the core: the core is
// relocated into each plugin and isn't part of any API. Rules for what goes in an
// API are in issue #42.
plugins {
    id("xena.java")
    `maven-publish`
}

val pluginProject = checkNotNull(project.parent) { "An API module lives in its plugin's folder, as <plugin>/api." }
val artifact = "${pluginProject.name}-api"

// Gradle tells projects apart by group and name, and every API project is named "api",
// so each gets its own group: otherwise a project using two plugins' APIs (xPlots with
// xUtilities' and xRewind's) silently gets only one. Published as co.xenastudios.
group = "co.xenastudios.${pluginProject.name}"
// The parent is configured first, so its version is already set.
version = pluginProject.version
base.archivesName = artifact

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        // Every public type and member is documented, or the build fails.
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
        addBooleanOption("quiet", true)
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            groupId = "co.xenastudios"
            artifactId = artifact
            from(components["java"])
            pom {
                name = artifact
                description = "The developer API of ${pluginProject.name}."
                url = "https://docs.xenastudios.co/${pluginProject.name}/"
                licenses {
                    license {
                        name = "GNU General Public License v3.0"
                        url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    }
                }
                scm {
                    url = "https://github.com/xena-studios/plugins"
                }
            }
        }
    }
    // Maven Central comes with the first released API (issue #42). Until then,
    // publishToMavenLocal is enough to try an API from another project.
    repositories {
        maven {
            name = "build"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
