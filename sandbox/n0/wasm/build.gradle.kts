import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The slice: jvm and wasmWasi from xena.kmp, as core-common has them, plus js and wasmJs (browser
// targets) to compare with neoskript-web's TeaVM build. Each target gets an executable so its
// production output can be measured; main() in commonMain keeps the slice reachable.
plugins {
    id("xena.kmp")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }
    js {
        nodejs()
        binaries.executable()
    }
}

// The probe and the benchmark print what they find.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

// Two tests fail on JS, as the README says (Kotlin/JS's Regex.matches with alternatives, and a bad
// regex throwing the browser's SyntaxError): the spike keeps them failing and reports them, without
// failing the build.
tasks.named<AbstractTestTask>("jsNodeTest") { ignoreFailures = true }
