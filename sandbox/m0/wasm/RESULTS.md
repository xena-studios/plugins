# M0 spike: Kotlin/Wasm and Pumpkin

Part of #121. A self-contained Gradle build, not part of the root build:

| Module | What it is |
|---|---|
| `common` | KMP, `jvm` + `wasmWasi`: a duration parser, a MiniMessage-subset markup parser into our own component tree, a `@Serializable` config class with JSON. `kotlin.test` on both targets. |
| `yaml` | Opt-in (`-Pm0.yamlRepo=<dir>`): kaml decoding `common`'s config on both targets, against a wasmWasi kaml built by `scripts/kaml-wasi.sh`. |
| `pumpkin-hello` | A Pumpkin plugin on pumpkin-api-kt that uses `common`: logs, parses, draws markup to Pumpkin text, tries WASI files, calls `pumpkin-peer` over IPC. |
| `pumpkin-peer` | A second plugin that answers IPC with `common`'s duration parser. |

```sh
./gradlew build                                    # tests on JVM and wasmWasi, both plugin components
scripts/kaml-wasi.sh /tmp/yaml && ./gradlew -Pm0.yamlRepo=/tmp/yaml/m2 :yaml:allTests
scripts/run-pumpkin.sh <pumpkin binary> <run dir>  # boots Pumpkin with both plugins
```

Versions: Kotlin 2.4.0 (pumpkin-api-kt requires it), Gradle 9.8.2, JDK 21 to run Gradle (JVM target 17), kotlinx-serialization 1.13.0, kotlinx-io 0.9.3, pumpkin-api-kt 0.1.2 and its Gradle plugin `org.pumpkinmc.plugin` 0.1.2 (both published, Maven Central and the Gradle Plugin Portal, 2026-11-04), wasm-tools 1.258.0 and Binaryen 130 (downloaded by that plugin). Pumpkin built from source at `a49be4b` (2026-11-23, `0.2.2+26.3-26.63`, wasmtime 50.0.1), `cargo build --release` in 16.5 min on an M1 Pro. WIT: pumpkin-plugin-wit `b831727`; the WIT inside pumpkin-api-kt 0.1.2 is identical to that commit's `v0.1/` (`diff -rq`).

## 1. Shared logic in a KMP `common` for JVM and wasmWasi

**Yes.** `./gradlew :common:allTests` runs the same 14 `kotlin.test` tests on the JVM and on wasmWasi (Node): durations (`1d2h`, `1d 2h 30m`, `1w3d`, `250ms`, errors with their index, format round-trip), markup (nesting, hex, aliases, `<!bold>`, `<click:run_command:/tp a:b>`, `<hover:show_text:'<red>Go > there'>`, `<reset>`, `\<`, unknown tags kept as text, junk never throws), config (defaults round-trip, partial file keeps defaults, unknown keys ignored, bad values as `Parsed.Err`), and the two clocks. All pass on both. A bug the tests caught (closing `</b>` didn't match the `bold` alias) failed identically on both targets, which is what we want.

What `common` must avoid, from compiling a probe file in `commonMain` (errors on the wasmWasi compile):

| Fails | Use instead (compiled on both) |
|---|---|
| `String.format` | string templates, our own formatters |
| `System.currentTimeMillis()` | `kotlin.time.Clock.System`, `TimeSource.Monotonic` |
| `java.util.UUID` | `kotlin.uuid.Uuid` |
| `synchronized`, `Thread` | `@kotlin.concurrent.Volatile`; no threads on Wasm at all |
| `a::class.java` | `a::class.qualifiedName` and `simpleName` work |
| any `java.*` | — |

Also: no file or network API in the stdlib on wasmWasi (kotlinx-io has files; see 3), no coroutines dispatcher with threads, no reflection beyond `KClass` names. `Regex`, `kotlin.time`, `kotlin.uuid` and `kotlinx.serialization` are fine.

**For the roadmap:** keep the rule as written, and enforce it the way this spike does: compile `wasmWasi` and run `wasmWasiTest` in CI from the first commit. Expect the clock and file caveats in 3 to apply to Pumpkin only, not to `common`'s tests.

## 2. kotlinx.serialization and YAML on wasmWasi

- **kotlinx.serialization: yes.** `kotlinx-serialization-json` 1.13.0 publishes `-wasm-wasi`; the config tests pass on both targets.
- **kaml: not as published.** `com.charleskorn.kaml:kaml:0.104.0` has wasmJs but no wasmWasi variant. Adding it to `commonMain` fails resolution: `No matching variant of com.charleskorn.kaml:kaml:0.104.0 was found ... attribute 'org.jetbrains.kotlin.wasm.target' with value 'wasi'`. Its dependency snakeyaml-engine-kmp 5.0.0 is the same (wasmJs only); yamlkt 0.13.0 has neither Wasm target. Of the libraries checked, only ktoml (TOML) publishes wasmWasi.
- **kaml built from source: yes.** snakeyaml-engine-kmp's wasmWasi target is commented out upstream only because kotest has no wasmWasi build ("Disable Wasi: No matching variant of io.kotest:kotest-framework-engine"). Enabling it needed three small `actual`s (environment variables, identity hash code, `isInteger`); kaml's own sources then compile for wasmWasi unchanged, against okio 3.18.2 (which publishes wasmWasi). `scripts/kaml-wasi.sh` does this from pinned commits; the `yaml` module's tests then decode a real YAML config into `common`'s `SpikeConfig`, encode it back, and report the line of a bad value, passing on JVM and wasmWasi (3/3 each). The identity-hash `actual` in the script is a spike-quality list, not weak, so it holds every object it hashes.

**For the roadmap:** YAML on Wasm is possible but not off the shelf. Options, best first: (a) upstream the wasmWasi target to snakeyaml-engine-kmp and kaml (small, and the blocker is only their test framework); (b) until then, publish our own build of both under our group; (c) a small YAML-subset reader of our own in `core-common` decoding into kotlinx.serialization. Don't pick a config format for Pumpkin's sake: Pumpkin is a late minor (M17), and (a) has time to land.

## 3. `common` in a Pumpkin plugin on a real server

**Yes, with one serious gap: WASI calls from Kotlin don't work inside pumpkin-api-kt callbacks.**

Both plugins build with `./gradlew build` and load on Pumpkin `a49be4b` with the **v0.1 (sync) ABI**: pumpkin-api-kt 0.1.2 embeds `pumpkin:plugin@0.1.0`, and Pumpkin's host runs it with `wasm_plugin_policy="LegacySyncReentry"`. v0.2 (async) needs a server built with `--features plugin-v0_2` ("Off until the v0.2 ABI is finished", `crates/pumpkin/Cargo.toml`) and has no Kotlin bindings yet. The log from `scripts/run-pumpkin.sh`:

```
INFO [plugin] [m0-peer] loaded, waiting for IPC
INFO Loaded xena-m0-peer (0.0.0)
INFO Found cached permission decision for plugin "xena-m0-hello" (approved: true)
INFO [plugin] [m0-hello] Hello from Kotlin/Wasm on Pumpkin
INFO [plugin] [m0-hello] Kotlin exceptions are catchable: boom
INFO [plugin] [m0-hello] Durations.parse("1d2h30m") = 1d 2h 30m (95400000 ms), formatted back: 1d2h30m
INFO [plugin] [m0-hello] Markup.parse plain: Welcome, Steve! [spawn]
INFO [plugin] [m0-hello] drawn to Pumpkin text (console): Welcome, Steve! [spawn]
INFO [plugin] [m0-hello] drawn to Pumpkin text (json): {"text":"","extra":[{"text":"","color":"#FFAA00","extra":[{"text":"Welcome, "},{"text":"","bold":true,"extra":[{"text":"Steve"}]},{"text":"!"}]},{"text":" "},{"text":"","click_event":{"action":"run_command","command":"/spawn"},"extra":[{"text":"","hover_event":{"action":"show_text","value":[{"text":"","color":"#55FF55","extra":[{"text":"Go"}]}]},"extra":[{"text":"","underlined":true,"extra":[{"text":"[spawn]"}]}]}]}]}
INFO [plugin] [m0-hello] data folder (host path): data
WARN [plugin] [m0-hello] WASI file access failed: IllegalStateException: ScopedMemoryAllocator is suspended when nested allocators are used
INFO [plugin] [m0-peer] IPC from xena-m0-hello: '1d2h30m' -> 'ok:95400000'
INFO [plugin] [m0-hello] IPC to xena-m0-peer replied: ok:95400000
INFO Loaded xena-m0-hello (0.0.0)
WARN Plugin "xena-m0-hello" uses the following permissions: ["fs.write.data"]
```

So `common`'s parsers and serializer run unchanged inside Pumpkin, our component tree draws to Pumpkin's `text-component` (colors, decorations, click and hover all come out right in the JSON), and Kotlin exceptions are catchable.

What went wrong, and why:

- **WASI from Kotlin fails inside every callback.** `kotlin.time.Clock.System.now()`, `TimeSource.Monotonic.markNow()` and kotlinx-io's `SystemFileSystem` (with `fs.write.data` granted and `data` preopened) all fail in `onLoad`, though the same clock calls pass under Node in `common`'s tests. The errors are `ScopedMemoryAllocator is suspended when nested allocators are used`, then `Can't create new allocators while realloc-allocated memory is not freed`, and for the clocks an uncatchable `thrown Wasm exception` that kills the store. Cause: pumpkin-api-kt's generated export bridge runs each callback inside `withScopedMemoryAllocator`, and the WASI preview1 reactor adapter calls back into Kotlin's `cabi_realloc` during the call; Kotlin's allocator refuses both. After a failure, host calls (even logging) keep failing until `freeAllComponentModelReallocAllocatedMemory()` is called (an internal, opt-in API, used in `HelloPlugin` only to keep the spike running). This is an upstream bug in pumpkin-api-kt (or the Kotlin wit-bindgen fork), not in Pumpkin; Pumpkin's host does set up WASI correctly (`crates/pumpkin-wasm-host/src/runtime.rs`, `preopened_dir(..., "data", ...)`).
- **A failed `Result` must hold a `ComponentException`.** The bridge does `result.exceptionOrNull() as ComponentException`; returning `Result.failure(anyOtherException)` from `onLoad` becomes a `ClassCastException` and an uncatchable trap. Our adapter layer must wrap.
- **pumpkin-api-kt in a multi-project build:** the Gradle plugin looks for `<project name>.wasm`, but Kotlin names it `<root>-<project>.wasm`, so `embedComponentWitRelease` fails with "Input file does not exist". Workaround in our build files: `(this as KotlinJsTargetDsl).outputModuleName.set(project.name)`.
- **Permissions prompt:** a plugin that asks for any permission is refused when the console isn't a TTY ("Console readline is not available; cannot prompt ... Permission denied ... skipping loading"). The answer is cached per plugin hash in `plugins/permission_cache.json`; `scripts/run-pumpkin.sh` writes it, as CI would.
- Each plugin compiles pumpkin-api-kt's generated bindings itself (~77,000 lines of Kotlin, as source: "WIT exports must be compiled as part of the final plugin"), about a minute per plugin. A clean `./gradlew build` of the whole spike is 91 s.
- Also noticed: Pumpkin sends anonymous telemetry heartbeats by default; plugins are reported "unsigned" (a warning only; `allow_unsigned` is on by default).

**For the roadmap:** M17 stays right to wait. Before `platform-pumpkin` starts: (1) file the WASI-allocator bug and the multi-project naming bug upstream (we can offer the fix); (2) until WASI works, a Pumpkin plugin has no files, no clock beyond server ticks, and no randomness from the stdlib, so the Pumpkin adapter must take time and storage from the host, which argues for `core-common` getting clocks and storage through interfaces the platform provides, never from the stdlib directly; (3) plan for the v0.2 async ABI, which pumpkin-api-kt doesn't support yet; (4) the core's failure type on Pumpkin has to map to `ComponentException`.

## 4. Plugin-to-plugin calls and storage, from the WIT

**Plugin-to-plugin: yes, as bytes.** `v0.1/ipc.wit` (and `v0.2/ipc.wit`, where it's `async`):

```wit
interface ipc {
    type plugin-id = string;
    type ipc-message = list<u8>;
    send-ipc-message: func(recipient: plugin-id, message: ipc-message) -> result<result<ipc-message, string>>;
}
```

and the plugin exports `handle-ipc-message: func(sender: plugin-id, message: ipc-message) -> result<ipc-message, string>` (`v0.1/plugin.wit`). It's one-to-one, synchronous request and reply (v0.1), addressed by the recipient's metadata `name` (`PluginManager::send_message` in `crates/pumpkin-core/src/plugin/mod.rs`; sending to yourself is an error). Load order follows `metadata.dependencies` (topologically sorted), which is how `pumpkin-hello` could call `pumpkin-peer` from `onLoad`. Proven above: `'1d2h30m' -> 'ok:95400000'`. There are no shared types, events or services across plugins: our `XMoney.api()` on Pumpkin would be a serialized protocol (kotlinx.serialization over IPC bytes), with a thin typed client in each plugin's API module.

**Storage:**

- **Files via WASI: in the contract, not working from Kotlin today.** The `fs.read.data` and `fs.write.data` permissions preopen the plugin's folder (`plugins/data/<name>`, which the host created) as `data` (`runtime.rs`; permissions in `crates/pumpkin-plugin-api/src/permissions.rs`). `context.get-data-folder()` exists in `v0.1/context.wit` and `v0.2/context.wit` (it returned `data` here). Blocked for Kotlin by the allocator bug in 3.
- **A database: only over the network.** No database interface in the WIT. Plugins can get `wasi:sockets` (`network.tcp.connect`, `network.outbound`, `network.dns`, optionally loopback-only) and `wasi:http` (`http.outbound`), so MySQL or a REST store is reachable in principle, but there's no Kotlin/Wasm MySQL driver and SQLite would need a Wasm build. Expect no SQL on Pumpkin.
- **Host key-value store: none.** Nothing in `v0.1` or `v0.2` (searched for storage, kv, database, persistent): no host KV, no persistent data container on players or worlds.

**For the roadmap:** for Pumpkin, the core's storage abstraction needs a file-backed implementation (JSON or our YAML in `data/`), not SQL; family features that share one database don't come to Pumpkin. Cross-plugin APIs on Pumpkin go over IPC bytes, so the API modules need a serializable request and reply form. xBackup "if its file access allows": it only sees its own `data` folder, so no.

## 5. Size and load time

| | `.wasm` component | gzip -9 | Pumpkin's compiled cache (`.cwasm`) |
|---|---|---|---|
| `pumpkin-peer` (bindings + `common`'s durations) | 293,271 B | 94,735 B | 2,473,984 B |
| `pumpkin-hello` (+ markup, JSON config, kotlinx-io, text drawing) | 524,094 B | 173,200 B | 5,306,400 B |

Load times on an M1 Pro, measured from process start to each log line by a script reading the server's stdout, both plugins:

- **Cold** (no compile cache, Cranelift compiles the components): 2.7–2.8 s until both are loaded (`Loaded xena-m0-peer` at 2,686 and 2,841 ms; hello 1–2 ms later).
- **Warm** (Pumpkin caches compiled components under `plugins/cache/` by hash and server version): both loaded 34–52 ms after the first plugin is found; `pumpkin-hello`'s `onLoad` (parsing, drawing, an IPC round trip) under 6 ms. Pumpkin's own "Started server; took" is 24–100 ms with the plugins and 11–48 ms without.

**For the roadmap:** today's Paper jars are 300–495 KB (1.0.1 releases). These components carry ~290 KB of bindings and runtime before any real logic, so a Pumpkin build of a plugin will likely be as big as its Paper jar or bigger (gzip helps where a store compresses). Warm loading is inside the 100 ms startup budget; the first start after every plugin update or server update pays about 1.5 s per plugin to compile, which the budget should state separately.
