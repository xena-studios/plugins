import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// A module of the rewrite that other plugins compile against (core-api, and each plugin's
// api/ later): xena.kmp, with Kotlin's own ABI validation in place of the Java APIs'
// apiDump. The signatures are committed in api/ beside the build file; `check` (and so
// `build`) fails when they change, so a change to an API is always deliberate: run
// ./gradlew <project>:updateKotlinAbi and commit the result.
plugins {
    id("xena.kmp")
}

kotlin {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation { }
}
