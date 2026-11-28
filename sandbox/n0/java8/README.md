# N0 spike: Java 8 class files from NeoSkript's compiler (results)

Part of #202. The question: can `neoskript-compiler` emit Java 8 (major 52) class files for scripts, so
NeoSkript can run on Spigot 1.8.8 to 1.16.5, and what does that cost? Everything below was run on
2026-11-27 (macOS arm64, Temurin 21.0.12, the CPU shared with other sessions, so timings are noisy).
The prototype is in `neoskript/` on branch `spike/n0-java8`, behind a flag; the default is unchanged.

## Summary

1. **Yes, and it's small.** The generated code needs only three things from Java 9 or later, all in how
   classes are defined and how they reach their constants, none in the code itself. A Java 8 target
   replaces them with a class loader for each class and static final fields: about 90 lines in
   `TriggerCompiler` and a 30-line `ScriptClassLoader`.
2. **Proved on 5,401 classes:** the compiler's corpus and 250 fuzzed scripts (1,222 classes) and the
   whole `neoskript-paper` test suite with the real standard library (4,179 classes), all compiled at
   major 52. Every class passes ASM's `CheckClassAdapter` verifier and the JVM's own verifier as it's
   defined. No invokedynamic, no dynamic constants, no nestmates, and every JDK class, method and field
   they reference is in JDK 8's `rt.jar`. All 128 compiler tests and 162 `neoskript-paper` tests pass
   with the Java 8 target.
3. **No measurable cost** on Java 21: the same speed for a hot loop, the same time to compile and define
   the corpus, the same metaspace for each class (about 3.7 KB). Not measured on a Java 8 JVM: none
   runs here.
4. **The real work is elsewhere:** the generated code calls into `neoskript-api` and `neoskript-runtime`
   (nothing else: no Bukkit, no addons), so those, and the compiler itself, have to run on Java 8. That's
   the Kotlin rewrite (N1) with `jvmTarget 1.8`, not the compiler's output.

## What the generated code needed from Java 9+

Read from `TriggerCompiler` (the only class that writes bytecode; `addons/reflect` only uses method
handles at runtime) and `runtime/generated/TriggerHost`:

| | Since | Where | On Java 8 |
|---|---|---|---|
| Class file version `V21` | 21 | `generate()`, `writer.visit(V21, ...)` | `V1_8`. ASM computes the stack map frames either way (`COMPUTE_FRAMES`), which 52 needs too |
| Hidden classes: `Lookup.defineHiddenClassWithClassData(bytes, constants, true)` | 15 (class data 16) | `define()`, through `TriggerHost.lookup()` | A `ScriptClassLoader` for each class (`defineClass`), so it's unloaded once unused, as a hidden class is |
| Dynamic constants: `ldc` of a `ConstantDynamic` bootstrapped by `MethodHandles.classDataAt` | 11 (`classDataAt` 16) | `constant()`, the only place: every element, `Class`, `FunctionHandle`, `Timespan` and so on the code uses | A `private static final` field for each, set in `<clinit>` from the loader (`takeConstants()`, once). The JIT folds static finals as constants, on 8 too |

Not used, so nothing to replace: `invokedynamic` of any kind (no `StringConcatFactory`: joined text is
already a `StringBuilder`; no `LambdaMetafactory`), nestmates (the class calls its own private `body`
with `invokespecial`, fine on 8), records, sealed classes, `VarHandle`s, private interface methods.
Static calls to interface methods (`invokestatic` with `itf`) are fine at 52.

JDK API the generated code references, all of it (the scan prints this): `Object.<init>`,
`Boolean.valueOf/TRUE/FALSE`, `Long.valueOf`, `Integer.valueOf/intValue`, `Double.valueOf`,
`String.valueOf(int)`, `StringBuilder.<init>/append(String)/toString`, `ArrayList.<init>`,
`List.add/toArray`, `Function.apply`, `Supplier.get`, the types `Throwable`, `Queue`, `CompletionStage`,
`BiConsumer`, and, new with the Java 8 target, `Class.getClassLoader`. Nothing from Java 9+ (no
`List.of`, `String.strip` and so on: those are in the compiler and the runtime, not in what it emits).

## The prototype

- `-Dneoskript.compiler.target=8` (read for each class, so a test can switch it) makes Java 8 classes.
  Anything else, the default, makes the Java 21 hidden classes as before.
- `runtime/generated/ScriptClassLoader`: `define(bytes)` and `takeConstants()`. Its parent is the
  runtime's loader, so a class sees exactly what a hidden class defined through `TriggerHost` sees.
- `-Dneoskript.compiler.dump=<dir>` writes every class the compiler makes, to check another module's.
- `Java8TargetTest` (compiler tests): compiles the corpus and fuzzed scripts at 8, checks each class's
  version, attributes and instructions, runs `CheckClassAdapter`, and with `-Pneoskript.jdk8.home=<JDK 8>`
  looks up every JDK reference in that JDK's `rt.jar` (following superclasses and interfaces).
  `-Pneoskript.java8.scan=<dir>` checks a dump the same way. It also prints the timings below.
- `StackFramesTest` now expects the script's frames in stack traces with the Java 8 target (see Risks).

Run it (from `neoskript/`):

```
./gradlew --max-workers=2 :neoskript-compiler:test -Pneoskript.compiler.target=8 \
    -Pneoskript.jdk8.home=/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home
./gradlew --max-workers=2 :neoskript-paper:test -Pneoskript.compiler.target=8 -Pneoskript.compiler.dump=/tmp/n0-classes
./gradlew --max-workers=2 :neoskript-compiler:test --tests '*Java8TargetTest' -Pneoskript.java8.scan=/tmp/n0-classes \
    -Pneoskript.jdk8.home=/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home
```

## Measured (on Java 21)

`Java8TargetTest.timesBothTargets` and `measuresMetaspacePerClass`. The load average was 15 to 33
during the runs, so only the quietest run's numbers mean much; none showed a difference beyond noise.

| | Java 21 hidden classes | Java 8 classes |
|---|---|---|
| A trigger looping 20,000 times (variables, arithmetic, a condition, an effect), quietest run | 2.86–2.95 ms a run | 2.85–2.87 ms a run |
| The same, four noisy runs | 3.6–8.3 ms | 3.4–10.1 ms |
| Parse, compile and define the corpus (408 classes), warm | 262–431 ms | 236–423 ms |
| Metaspace for each class, warm | 3,779 bytes | 3,728 bytes |

## What N1 and N4 should do

- **N1 (the Kotlin engine):** keep the two differences (version, how a class is defined and gets its
  constants) behind one small interface, chosen once at startup from the JVM's version, as the decision
  says ("scripts are compiled for the server's own Java"): hidden classes where the JVM has them (16+),
  a loader for each class below. Or, simpler, emit 52 everywhere with the loader: it cost nothing here,
  and there'd be one path to test. Decide with N1's benchmarks on Paper.
- **N1:** keep the rule that generated code calls only `neoskript-api`, `neoskript-runtime` and Java 8's
  API. Make it a test: run this scan (or `animal-sniffer`-style signatures) over every class the
  compiler's and the standard library's suites make, on every pull request, not just in this spike.
- **N1:** the compiler and runtime themselves must run on Java 8: today they use records, pattern
  `switch`, `List.of`, `strip`, `getFirst` and so on throughout (hundreds of uses, see `grep`). In
  Kotlin with `jvmTarget 1.8` and `-Xjdk-release=1.8` most of that goes away; ASM 9 runs on 8.
- **N4 (1.16.5, 1.13, 1.8.8):** boot the Java 8 target on a real Java 8 server and run Skript's suite and
  the differential tests there, with `-Xverify:all`. This spike couldn't run a Java 8 JVM (below).
- **N4:** if a line in a thread dump matters, emit a `LineNumberTable`: Java 8 classes show up in stack
  traces, so the watchdog's dump could name the script and line itself.

## Risks and what wasn't verified

- **No Java 8 JVM ran any of this.** The installed Temurin 8 is x86_64 and the Mac has no Rosetta, and
  the runtime it would call is Java 21 anyway. The proof is ASM's verifier, Java 21's verifier as each class is
  defined (the same type-checking verifier Java 8 uses for major 50 and up) and the `rt.jar` scan. The `rt.jar` scan checks that
  members exist, not their access. Java 8's verifier and JIT should behave the same; N4 confirms it.
- **Speed on Java 8 not measured.** Static finals fold on Java 8's C2 as on 21's, but the whole engine
  on Java 8 (older GC, JIT, boxing) is N4's benchmark.
- **Stack traces differ.** Hidden classes are left out of stack traces; Java 8 classes aren't, so
  `co.xenastudios.neoskript.runtime.generated.Trigger.body(<script>.sk)` frames appear in errors and
  thread dumps. Mostly better, but anything that prints or filters traces should expect them.
- **A loader for each class.** Cheap here (metaspace the same), but on Java 8 each loader also gets its
  own metaspace chunks and a heap object; a server with tens of thousands of triggers should be checked
  in N4. A loader for each script, with unique class names, is the fallback.
- **The compiled class is no longer in `TriggerHost`'s runtime package**, so it can't reach that
  package's non-public members. It never did need them (all tests pass), but any future code that relies
  on the hidden class's package access must use public API.
- **The constants hand-off is public** (`ScriptClassLoader.takeConstants()`), as a class in another
  loader has to call it. It hands them out once, then holds nothing.
- `<clinit>` sets one field per constant (about 11 bytes each), so a single class could hold some 5,900
  constants before hitting the 64 KB method limit; the constant pool limit applies either way. No script
  in the corpus comes close.
