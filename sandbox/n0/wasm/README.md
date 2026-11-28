# N0 spike: NeoSkript's frontend on wasmWasi (and the browser)

Part of #202. The question: what does it take for `neoskript-frontend` (the parser and type checker, 59 main files, 12,867 lines of Java, no Bukkit) to build as Kotlin Multiplatform for the JVM and `wasmWasi`, and for the website, and is that worth it over today's TeaVM build in `neoskript-web`?

A Gradle build of its own, like `sandbox/m0/wasm`, but on the rewrite's own conventions: `xena.kmp` from `build-logic` (as `core/core-common`), plus `wasmJs` and `js` to compare browser targets. It holds a slice of the frontend converted to Kotlin in `commonMain`: the whole `pattern` package (Skript's pattern language: compiler, backtracking matcher, `<regex>` parts, the literal index, balanced spans), `parse/Literals` (the regex-heavy literal recognizer) and `source/Text`. That's 1,464 lines of Java, 11% of the frontend, as 1,137 lines of Kotlin.

```sh
../../../gradlew -p sandbox/n0/wasm --max-workers=2 jvmTest wasmWasiTest wasmJsTest jsNodeTest
../../../gradlew -p sandbox/n0/wasm compileProductionExecutableKotlinWasmWasiOptimize \
    compileProductionExecutableKotlinWasmJsOptimize jsProductionExecutableCompileSync   # sizes
```

Versions: Kotlin 2.4.21 (the repo's catalog), Gradle 9.8.2, Node from Kotlin's Gradle plugin for the tests, Node 24.21 locally for the production benchmark, TeaVM 0.16.0 (`neoskript/gradle/libs.versions.toml`). Measured on 2026-11-27 on a shared M1 Pro (other sessions were building), so timings are rough: best of 15 rounds, and they moved by up to 2x between runs.

## 1. Does it build and pass? Yes, on JVM, wasmWasi and wasmJs. Not on JS.

The slice's tests are `neoskript-frontend`'s `PatternTest`, test for test, plus a `LiteralsTest` and a check that all 2,127 of Skript 2.17's patterns compile (from `2.17.0.json`, turned into code, since Wasm has no files). 27 tests each:

| Target | Passed | Notes |
|---|---|---|
| jvm (Java 8 class files, `-Xjdk-release=1.8`) | 27/27 | |
| wasmWasi | 27/27 | |
| wasmJs | 27/27 | |
| js | 25/27 | `Regex.matches` wrong with alternatives; a bad regex throws the browser's `SyntaxError`, not `IllegalArgumentException` |

`jsNodeTest` has `ignoreFailures` so the build passes while the two failures still show. The benchmark matches 566 (line, pattern) pairs on every target, the same as the Java original.

The conversion was mechanical except in three places, and all three will come back across the frontend:

- **Whitespace.** Kotlin's `Char.isWhitespace()`, `trim()` and `isBlank()` treat no-break spaces (U+00A0, U+2007, U+202F) as whitespace; Java's `Character.isWhitespace`, `strip()` and `isBlank()` (and so Skript) don't. `Text.isWhitespace/strip/isBlank` keep Java's meaning, and a test pins it. Java's `\s` is ASCII only; `Literals` spells its whitespace class out.
- **Regex regions.** `Matcher.region(pos, to).matches()` became `matchEntire(input.substring(pos, to))`, which means the same with Java's default bounds, but the groups' offsets are now relative to `pos`. `PlaceholderResolver.resolveRegex` documents it; `LineParser`'s one implementation has to add `start`.
- **Case-insensitive regexes.** On the JVM, Kotlin's `RegexOption.IGNORE_CASE` adds `UNICODE_CASE`, which Java's `CASE_INSENSITIVE` alone doesn't: `É` matches `é`, and `ſ` (long s) matches `s`. So `1 ſecond` would become a timespan, which it isn't in Skript. The frontend's case-insensitive regexes are all ASCII words, so the fix is cheap: lower-case with `Text.lower` and match case-sensitively. Not done in the slice; the probe shows the difference.

## 2. What the frontend uses from the JDK

Counted over `src/main` (59 files). The left column is mechanical (Kotlin's stdlib has it in common), the rest needs a decision.

| Use | Count | Files | In common |
|---|---|---|---|
| `List/Map/Set/ArrayList/HashMap/LinkedHashMap/ArrayDeque/EnumMap/EnumSet`, `Collections`, `Arrays`, `Objects`, `Optional` | ~140 imports | most | Kotlin collections; `EnumMap/EnumSet` become maps and sets |
| Streams (`.stream()`, `Collectors`) | 71 | 13 (LineParser 34) | collection operations |
| Records | 78 | 26 | data classes (or plain classes, see identity maps) |
| Sealed interfaces, pattern `switch`, `instanceof` patterns | 90 | 14 | sealed interfaces, `when`, smart casts |
| `StringBuilder`, `"x".repeat`, `chars().filter` | ~40 | 9 | yes |
| `Character.isLetter/isLetterOrDigit/isDigit` | 10 | 7 | `Char.isLetter()` etc., Unicode tables of Kotlin's own on Wasm/JS |
| `Character.isWhitespace`; `strip()`, `isBlank()` | 13; 103 | 10; most | **not the same in Kotlin**: `Text` helpers (1) |
| `toLowerCase(Locale.ROOT)`, `toUpperCase(Locale.ROOT)` | 16 | 5 | `lowercase()`/`uppercase()` are locale-free on every target |
| `Integer.parseInt`, `Long.valueOf`, `Double.parseDouble` | 13 | 5 | `toInt()`, `toLong()`, `toDouble()` |
| `String.format` | 1 | Json | a template |
| **`java.util.regex`**: `Pattern`, `Matcher`, `matches/replaceAll/split/replaceFirst` | 152 | 17 (CommandParser 27, StatementParser 27, ScriptLoader 24, Literals 20, LineParser 17) | `kotlin.text.Regex`; see 3 |
| **`IdentityHashMap`**, `newSetFromMap(IdentityHashMap)`, `System.identityHashCode` | 21 | 5 (StatementParser 7, ParsedScript 6, ScriptLoader 4) | none. Keyed by `Line`, `Statement`, `Structure`: make those plain classes (identity `equals`) instead of data classes, or give them an id |
| **`WeakHashMap`** (synchronized) caches keyed by registry | 3 | ParserContext, Plurality | none. Hold the cache on the registry instead |
| **`ConcurrentHashMap`, `ThreadLocal`, `volatile`** | 12 | ParserContext, LoadingScript, WarningSettings, Plurality | no threads on Wasm (M0). `ThreadLocal<String>` (the script being loaded) should become a parameter; `@Volatile` exists in common; the concurrent maps are only needed on the JVM (expect/actual, or pass in) |
| **`System.nanoTime`**, `System.currentTimeMillis`, `Long.getLong(...)` (a system property) | 5 | ParserContext, WarningSettings | `TimeSource.Monotonic`; the time limit comes in as a setting |
| **`catch (StackOverflowError)`** | 3 | StatementParser, ScriptLoader (2) | **can't be done on Wasm**; see 4 |
| **Resources**: `getResourceAsStream("plurality.txt")` | 1 | Plurality | no resources in common: generate a Kotlin file from it at build time |
| Reflection | `.class` for `EnumMap`/`EnumSet`/`isInstance` only | 4 | `is` checks; nothing else reflective. No `ServiceLoader` |
| Files, `BigDecimal`/`BigInteger`, `java.time` | 0 | | |

From `neoskript-api` the frontend uses four small types (`ScriptSyntax`, `ScriptStructure` and its `Entry`, `StructureHandler`, `ScriptSyntaxHandler`): a record, two enums and three interfaces with `java.util.List`. They'd move to common with it. The rest of the API (`CompletionStage`, the element interfaces) stays JVM.

Tests: of 11 test files and 6 fixtures, only two read files: `SkriptSyntax` (Gson, `getResourceAsStream` of `2.17.0.json`) and `ScriptFuzzer` (`Files.walk` over the repo's `.sk` files, `System.getProperty`). On Wasm the data has to be code or come in through kotlinx-io (M0: files work under Node's WASI); the fuzzer can stay a JVM test of the common code. JUnit becomes `kotlin.test`; nothing uses parameterized tests.

## 3. Regex: the real work

`kotlin.text.Regex` is three engines: Java's `Pattern` on the JVM, Kotlin's own (a port of Harmony's) on wasmWasi and wasmJs alike, and the browser's `RegExp` on JS. `RegexProbe` runs 39 cases on each target; the differences from the JVM:

| Case | JVM (Java) | wasmWasi, wasmJs | js |
|---|---|---|---|
| `\s` takes U+00A0 / U+2003 | no / no | no / no | **yes / yes** |
| `\b` between `é` and `x` | yes | **no** (`é` is a word character) | yes |
| `\p{IsAlphabetic}`, `\p{javaLowerCase}` | yes | **throws** | **throws** |
| `(?i)é` matches `É` (inline flag) | no | **yes** | **throws** |
| `(?i)` at the start, or mid-pattern | yes | yes | **throws** |
| `(?s)` | yes | yes | **throws** |
| possessive `a*+`, atomic `(?>...)`, class intersection `&&`, `\Q...\E`, `\h`, `\A`/`\z` | Java's | same as Java | **throw** |
| `Regex("a\|ab").matches("ab")` | yes | yes | **no** (Kotlin/JS bug) |
| `split("\\s+")` of `a  b c` | 2 parts | 2 | **3** |
| a bad regex throws | `PatternSyntaxException` (an `IllegalArgumentException`) | the same | a JS `SyntaxError` |

Agreeing everywhere: `\p{L}`, `\p{Sc}`, `\p{N}`, `\p{Nl}`, `\p{No}`, `\p{Alpha}`, `IGNORE_CASE`, named groups (`groups["b"]`), lookahead and lookbehind, backreferences, `$1` in replacements, `.` on a surrogate pair, `Regex.escape`.

So:

- **Kotlin/Wasm is close to Java.** Its gaps are `\b` next to non-ASCII letters, Java's own `\p{Is...}` and `\p{java...}` classes, and Unicode case folding being always on. None of the frontend's own regexes hit them (they compile and pass). Skript 2.17's registered `<regex>` parts are a handful of simple ones (`.+`, `(.+)`, `.*`, `\d+`, `(\d+)`, `-?\d+(_\d+)*`, ordinals). Add-ons' regexes are Java's dialect, and the server checks them with Java's engine on the JVM anyway.
- **Kotlin/JS isn't usable for this code.** The frontend writes `(?i)` 9 times and `DOTALL` twice (and `CASE_INSENSITIVE` 14 times, which is fine), `Literals` relies on `matches` with alternatives, and add-on regexes are Java's dialect. Each would need rewriting or wrapping, and `\s` silently means something else. TeaVM doesn't have this problem: it ships its own `java.util.regex`.
- **The slice uses `RegexOption.IGNORE_CASE`, never `(?i)`**, and N1 should do the same. Common code has only `IGNORE_CASE` and `MULTILINE`; `DOTALL` has to be `(?s)` or `[\s\S]`.

## 4. Stack overflow can't be caught on Wasm

`StatementParser` and `ScriptLoader` catch `StackOverflowError` to report a line that's too deeply nested instead of failing. `StackProbe` (ignored, as it fails the run; remove `@Ignore` to see it):

| Target | Deep recursion |
|---|---|
| jvm | caught: `StackOverflowError` |
| js | caught (a JS `RangeError`) |
| wasmWasi, wasmJs | **not caught**: `RangeError: Maximum call stack size exceeded` goes past Kotlin's `catch (Throwable)` to the host and the test fails. Under wasmtime (Pumpkin) it's a trap, and the instance is gone |

So common code needs an explicit depth limit (a counter in `ParserContext`, failing with the same "too deep" error), not a catch. This is also what makes the "no script can crash it" rule hold on Wasm, so it matters more than its line count. A depth limit is deterministic too, where the JVM's depends on the thread's stack size.

## 5. Sizes

The production executables, with `main()` in `commonMain` calling the compiler, the matcher and `Literals`, so dead-code elimination keeps the slice. Bytes, and gzip -9:

| Build | Hello world | One `Regex` | The slice |
|---|---|---|---|
| wasmWasi (Binaryen optimized) | 10,725 / 5,208 | 175,957 / 69,296 | **217,393 / 84,900** |
| wasmJs (Binaryen optimized) | 575 / 344 | 165,261 / 66,048 | **204,592 / 80,962** (+ 10 KB of `.mjs` glue) |
| js (all `.js` files) | 32,515 / 6,269 | 88,191 / 16,173 | **265,285 / 45,967** |
| TeaVM 0.16, **the whole frontend** + `neoskript-web` | | | **1,022,928 / 277,129** |

Kotlin's regex engine is about 165 KB of the Wasm builds, paid once. The slice's own code adds about 40 KB. Scaled by lines (the slice is 11% of the frontend), the whole frontend on wasmJs would be roughly 165 KB + 350–400 KB, so about 0.5–0.6 MB and 200–230 KB gzipped: about half of TeaVM's bytes and a little under its gzipped size. That's an extrapolation, not a measurement. The syntax data the page downloads (`syntax.json`, 368,681 bytes) is the same either way.

## 6. Speed

`Benchmark` compiles Skript 2.17's 2,127 patterns, then matches the website examples' 91 statement lines against all of them through the index with a resolver that takes any text. Best of 15, ms:

| Run | Compile 2,127 | Match 91 lines |
|---|---|---|
| Java original, JVM (`neoskript-frontend`'s classes) | 5.4–5.6 | 6.7–6.9 |
| Kotlin, jvm | 4.1–5.7 | 6.1–10.8 |
| Kotlin, wasmWasi, production + Binaryen, Node 24 | 10.6 | 21.3 |
| Kotlin, wasmJs, production + Binaryen, Node 24 | 8.2 | 20.9 |
| Kotlin, wasmWasi / wasmJs, development (the Gradle tests) | 10–17 | 30–64 |
| Kotlin, js, development | 9.4–12.2 | 15–18 |

The production Wasm runs are `startUnitTests()` from `compileTestProductionExecutableKotlinWasm{Wasi,Js}Optimize`'s output under Node. So Kotlin on the JVM is as fast as the Java it replaces, and Wasm is about 3x slower than the JVM. For comparison, TeaVM's whole parser, timed the same way over the three website examples (`init` with `syntax.json`, then `parse` each): 164 ms to init and 40.7 ms to parse, against 94 ms and 14.5 ms for `WebParser` on the JVM, so also about 2.8x. These are different workloads, so they say Wasm is in TeaVM's range, not which is faster. In both, most of the work in a real parse is the frontend's own code rather than the regex engine.

## 7. Recommendation for N1

1. **Convert the frontend straight into common code** (jvm + wasmWasi + wasmJs), not JVM-first and move later. On the slice, writing common Kotlin cost almost nothing over JVM Kotlin. The costly part is finding JVM-only habits (whitespace, `IGNORE_CASE`, identity maps, `ThreadLocal`, catching stack overflow), and the wasmWasi compile and tests find them on each pull request, as `core-common`'s rule intends. Converting JVM-first would let those habits spread through 12,000 lines and then have to be found again. Do the JDK-specific parts first, in their own pull requests, still in Java where possible: a depth limit instead of `catch (StackOverflowError)`, the script being loaded passed in instead of a `ThreadLocal`, the per-registry caches moved onto the registry, `plurality.txt` as code, Java-meaning whitespace helpers.
2. **Build the website's checker from wasmJs, not wasmWasi and not Kotlin/JS.** wasmJs has the same regex engine as wasmWasi (the probe's answers are identical), exports to JavaScript directly, and runs in browsers and Node (the language server) without a WASI shim. Kotlin/JS fails the tests and has a different regex dialect. wasmWasi stays the Pumpkin and CI target. This means the plan's "wasmWasi for the website" should read "Kotlin/Wasm (wasmJs) for the website".
3. **Keep TeaVM until the converted frontend passes in wasmJs**, then switch `neoskript-web` in one pull request. The rest of the Java frontend can't be built with Kotlin, so TeaVM stays the website's build for all of N1's conversion, and costs nothing to keep meanwhile.
4. **Keep the regex dialect deliberate:** `IGNORE_CASE` and lower-casing, never inline `(?i)` or `(?s)`; `Text.isWhitespace` instead of `isWhitespace()`; a test of every `<regex>` from Skript and the bundled add-ons compiling on wasmJs.

## 8. Risks

- **Regex dialect drift** between the server (Java's engine) and the website and Pumpkin (Kotlin's): an add-on's regex could compile on the server and throw, or match differently, in the browser. It's narrow (see 3), and the frontend can report "this regex isn't supported here" instead of crashing, but the differential tests run only on the JVM. A probe like `RegexProbe` in CI, run on every engine, is the guard.
- **Unicode tables.** On Wasm, `Char.isLetter()` and `\p{L}` use Kotlin's own tables, not the JDK's, so a newly assigned character can be a letter on one and not the other. Rare in scripts.
- **Speed in the browser.** Wasm is about 3x slower than the JVM on the slice, about what TeaVM is today. The N1 exit says "benchmarks no slower", and that's against the JVM. The browser needs its own benchmark against TeaVM's numbers here.
- **Browser support.** Kotlin/Wasm needs WasmGC and Wasm exception handling, and this build asks for the `js-string` builtins. Not checked here: which browsers Kotlin 2.4's wasmJs output runs in, and whether the page needs a fallback.
- **Identity maps and data classes.** Converting records to data classes changes `equals`. Anything kept in an `IdentityHashMap` today has to stay a plain class, or lookups will silently merge equal lines.
- **Deep recursion.** The matcher recurses once per pattern element and per continuation. Wasm's stack under Node and wasmtime is smaller than the JVM's default, so a line that's fine on the server could hit the depth limit, or overflow, in the browser. The depth limit has to be set for the smallest target.

Not verified: the whole frontend's size and speed on Wasm (extrapolated from the slice); TeaVM's own Wasm backend (TeaVM 0.10+ has one) as a third option; browser versions; how TeaVM handles stack overflow and `\b`; a wasmWasi build under wasmtime rather than Node.
