# N0 spike: NeoSkript's risky parts, tried before the rewrite (results)

Part of #202. NeoSkript joins the rewrite (decisions/2026-11-27-neoskript-joins-the-rewrite.md): in Kotlin, on the shared core, down to Spigot 1.8.8, on Fabric and beyond. Before converting anything, the spike tried what could sink that plan. What it decided is in decisions/2026-11-28-n0.md. All of it was measured on a shared M-series Mac with other sessions' builds running, so the timings are loose.

| | Question | Answer | Where |
|---|---|---|---|
| Java 8 | Can the compiler write scripts as Java 8 class files, which Spigot 1.8.8 to 1.16.5 need? | Yes, behind `-Dneoskript.compiler.target=8`, at no measured cost. Only three things needed newer Java: the class file version, hidden classes and dynamic constants. 5,401 classes from the corpus, fuzzed scripts and the standard library's tests pass ASM's and the JVM's verifiers at version 52 and call only Java 8's API; the compiler's and the standard library's suites pass at target 8. The rest of Java 8 is N1's: the compiler and runtime themselves use Java 9+ throughout. | [java8/](java8/README.md), and the flag in `neoskript/` |
| Wasm | Can the parser be shared code, for the JVM, the website, the language server and Pumpkin? | Yes, with one change: the website's build is **wasmJs**, not Kotlin/JS or wasmWasi. 11% of the frontend in Kotlin passes its tests on the JVM, wasmWasi and wasmJs; Kotlin/JS's regex gets Skript's patterns wrong. The whole frontend on wasmJs comes to roughly 200 to 230 KB gzipped, against TeaVM's 277 KB today. Wasm can't catch a stack overflow, so the frontend needs a depth limit of its own. | [wasm/](wasm/README.md) |
| Game model | What sits between Skript's syntax and each platform, and what does it cost? | The platform's own objects as values and neutral operations on them, no wrapper per value. One hand-written script passed on Paper, Folia and Fabric 1.21.11 and Fabric 26.3, and a real client's seven checks on Paper, Folia and Fabric. Within the noise on Paper; 1 to 11 ns a call on Fabric. About half of it belongs in the core. | [model/](model/README.md), its code on the `spike/n0-model` branch |
| Jar | Fetch ASM at runtime, as the plugins fetch their libraries? | Not worth it alone: ASM is about 130 KB of the 2.2 MB jar, the standard library about 1 MB and the frontend and compiler about 0.5 MB. One jar per platform and R8 are where the size is. | below |

## The jar today

NeoSkript 0.1.0-beta.1's jar, 2,197 KB, by package (uncompressed class sizes):

| | KB |
|---|---|
| `paper` (the standard library) | 1,049 |
| `frontend` | 265 |
| `compiler` | 227 |
| `libs` (ASM and bStats, shaded) | 144 |
| `runtime` | 143 |
| `plugin` | 112 |
| `api` | 22 |

## Not tried

- A Java 8 JVM running anything: the one installed is x86_64 and there's no Rosetta. N4 boots real Java 8 servers.
- Spigot, CraftBukkit and anything before 1.21.11 for the game model; the 1.8.8 id tables are a design only.
- The whole frontend's Wasm size and speed (scaled up from the slice), and which browsers run Kotlin's wasmJs output.
