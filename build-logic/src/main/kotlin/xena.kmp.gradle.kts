import co.xenastudios.gradle.KotlinConventions
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

// A module of the rewrite's shared logic: Kotlin Multiplatform for the JVM and for
// wasmWasi, the target Pumpkin's plugins are built for. Nothing from java.* compiles for
// wasmWasi, so building that target keeps the JVM out of shared code (ROADMAP.md,
// Architecture). Tests are kotlin.test, run on both: `jvmTest` and `wasmWasiTest` (Node,
// which Kotlin's Gradle plugin downloads, pinned to the version it was tested with).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Every public declaration says it's public and gives its type, so nothing becomes
    // API by accident.
    explicitApi()
    compilerOptions { KotlinConventions.common(this) }

    jvm {
        compilerOptions { KotlinConventions.jvm(this) }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

KotlinConventions.javaRelease(project)

// Node comes from the repository settings.gradle.kts declares: projects can't add their own.
plugins.withType<WasmNodeJsPlugin> {
    the<WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
