#!/usr/bin/env bash
# Builds kaml and snakeyaml-engine-kmp for wasmWasi (neither publishes a wasmWasi variant) into a
# local Maven repository, for the `yaml` probe:
#   scripts/kaml-wasi.sh <work-dir>
#   ./gradlew -Pm0.yamlRepo=<work-dir>/m2 :yaml:allTests
set -euo pipefail
spike=$(cd "$(dirname "$0")/.." && pwd)
work=$(mkdir -p "$1" && cd "$1" && pwd)
repo="$work/m2"
cd "$work"

# snakeyaml-engine-kmp: the wasmWasi target is commented out upstream only because kotest has no
# wasmWasi build; enable it, supply the three missing actuals, and skip signing.
[ -d snakeyaml-engine-kmp ] || git clone -q https://github.com/krzema12/snakeyaml-engine-kmp
git -C snakeyaml-engine-kmp checkout -q 61c6083
python3 - "$work/snakeyaml-engine-kmp" <<'EOF'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
mpp = root / "buildSrc/src/main/kotlin/buildsrc/conventions/lang/kotlin-multiplatform.gradle.kts"
mpp.write_text(mpp.read_text().replace(
    "    //wasmWasi {\n    //    binaries.library()\n    //    //nodejs()\n    //}",
    "    wasmWasi {\n        binaries.library()\n        nodejs()\n    }"))
pub = root / "buildSrc/src/main/kotlin/buildsrc/conventions/publishing.gradle.kts"
pub.write_text(pub.read_text().replace("    signAllPublications()", "    // signAllPublications()"))
internal = root / "src/wasmWasiMain/kotlin/it/krzeminski/snakeyaml/engine/kmp/internal"
(internal / "system.wasi.kt").write_text('''package it.krzeminski.snakeyaml.engine.kmp.internal

internal actual fun getEnvironmentVariable(key: String): String? = null

// Spike only: an identity map with no weak references, so it holds what it has seen.
internal actual fun objectIdentityHashCode(any: Any): IdentityHashCode = IdentityHashCode(identityHash(any))

private val ids = mutableListOf<Any>()
private fun identityHash(any: Any): Int {
    val i = ids.indexOfFirst { it === any }
    if (i >= 0) return i + 1
    ids += any
    return ids.size
}
''')
(internal / "math.wasi.kt").write_text((root / "src/wasmJsMain/kotlin/it/krzeminski/snakeyaml/engine/kmp/internal/math.wasm.kt").read_text())
EOF
(cd snakeyaml-engine-kmp && ./gradlew -q -Dmaven.repo.local="$repo" \
  publishKotlinMultiplatformPublicationToMavenLocal publishWasmWasiPublicationToMavenLocal publishJvmPublicationToMavenLocal)

# kaml: its build pins kotest (no wasmWasi), so compile its sources in a small build of our own.
[ -d kaml ] || git clone -q https://github.com/charleskorn/kaml
git -C kaml checkout -q 618b881
mkdir -p kaml-wasi
cp -R "$spike/gradle" "$spike/gradlew" kaml-wasi/
cat > kaml-wasi/settings.gradle.kts <<'EOF'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "kaml"
EOF
cat > kaml-wasi/build.gradle.kts <<EOF
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    \`maven-publish\`
}
group = "com.charleskorn.kaml"
version = "0.104.0-wasi"
repositories {
    maven(uri("$repo"))
    mavenCentral()
}
kotlin {
    jvmToolchain(17)
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi { nodejs() }
    sourceSets {
        commonMain {
            kotlin.srcDir("../kaml/src/commonMain/kotlin")
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.13.0")
                implementation("it.krzeminski:snakeyaml-engine-kmp:5.0.1-SNAPSHOT")
                implementation("com.squareup.okio:okio:3.18.2")
            }
        }
        jvmMain { kotlin.srcDir("../kaml/src/jvmMain/kotlin") }
    }
}
publishing { repositories { maven(uri("$repo")) { name = "scratch" } } }
EOF
(cd kaml-wasi && ./gradlew -q publishAllPublicationsToScratchRepository)
echo "Published to $repo"
