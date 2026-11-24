plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios.core"
description = "What the rewrite's plugin APIs share, the same on every platform."

kotlin {
    compilerOptions {
        // Pending is an expect class: CompletableFuture on the JVM, our own promise on Wasm
        // (DECISIONS.md). Expect classes are Beta, which is a warning without this.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
