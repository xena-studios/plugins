package co.xenastudios.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/**
 * The compiler settings every module of the rewrite shares, on every target (xena.kmp,
 * xena.kotlin-jvm). The reasons are in DECISIONS.md.
 */
internal object KotlinConventions {

    /**
     * The Java bytecode of the core and the Bukkit jar: Spigot 1.13–1.16.5 refuse to start on
     * Java 17, so Java 8 is the one level every server we reach can load (M0).
     */
    const val JAVA_RELEASE = 8

    /** Every target: warnings fail the build, as `-Werror` does for the Java modules. */
    fun common(options: KotlinCommonCompilerOptions) {
        options.allWarningsAsErrors.set(true)
    }

    /**
     * The JVM targets: Java 8 class files, compiled against Java 8's API too
     * (`-Xjdk-release`), so a call to anything newer fails here rather than on an old server.
     */
    fun jvm(options: KotlinJvmCompilerOptions) {
        options.jvmTarget.set(JvmTarget.JVM_1_8)
        options.freeCompilerArgs.add("-Xjdk-release=1.8")
    }

    /** Java sources next to the Kotlin ones (a shim, later) compile to the same level. */
    fun javaRelease(project: Project) {
        project.tasks.withType<JavaCompile>().configureEach {
            options.release.set(JAVA_RELEASE)
            options.encoding = "UTF-8"
            // JDK 21+ warns that release 8 is obsolete; that's the point here.
            options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
        }
    }
}
