/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.xenastudios.neoskript.compiler.fuzz.CompilerFuzzTest;
import co.xenastudios.neoskript.frontend.parse.ParsedScript;
import co.xenastudios.neoskript.frontend.parse.ScriptLoader;
import co.xenastudios.neoskript.frontend.source.SourceFile;
import co.xenastudios.neoskript.frontend.stub.ScriptFuzzer;
import co.xenastudios.neoskript.runtime.SimpleTriggerContext;
import co.xenastudios.neoskript.runtime.Trigger;
import co.xenastudios.neoskript.runtime.variables.VariableStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * The compiler's Java 8 target (#202, an N0 spike): the corpus and fuzzed scripts compile to major 52 classes that
 * pass ASM's verifier, use no invokedynamic, dynamic constants or nestmates, and reference only Java 8's API (checked
 * against a JDK 8's rt.jar when {@code neoskript.jdk8.home} names one). Also times the two targets' code.
 */
class Java8TargetTest {

    @Test
    void theCorpusCompilesToJava8Classes() throws IOException {
        List<byte[]> classes = Collections.synchronizedList(new ArrayList<>());
        withTarget8(classes, () -> {
            var library = CompilerFuzzTest.library();
            List<String> originals = ScriptFuzzer.fromRepository().originals();
            for (int i = 0; i < originals.size(); i++) {
                ScriptFuzzer.Case fuzzCase = new ScriptFuzzer.Case(-i, originals.get(i));
                ScriptFuzzer.within(fuzzCase, 90, () -> CompilerFuzzTest.compileAndRun(library, fuzzCase));
            }
            ScriptFuzzer fuzzer = ScriptFuzzer.fromRepository();
            long first = ScriptFuzzer.firstSeed();
            for (long seed = first; seed < first + ScriptFuzzer.iterations(250); seed++) {
                ScriptFuzzer.Case fuzzCase = fuzzer.generate(seed);
                ScriptFuzzer.within(fuzzCase, 90, () -> CompilerFuzzTest.compileAndRun(library, fuzzCase));
            }
        });
        assertFalse(classes.isEmpty());
        check(classes);
    }

    /** The classes in {@code neoskript.java8.scan}, written there by another module's tests with the dump property. */
    @Test
    void dumpedClassesAreJava8() throws IOException {
        String scan = System.getProperty("neoskript.java8.scan");
        org.junit.jupiter.api.Assumptions.assumeTrue(scan != null && !scan.isBlank());
        List<byte[]> classes = new ArrayList<>();
        try (var files = Files.list(Path.of(scan.strip()))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                classes.add(Files.readAllBytes(file));
            }
        }
        assertFalse(classes.isEmpty());
        check(classes);
    }

    private static void check(List<byte[]> classes) throws IOException {
        Map<String, Integer> jdkRefs = new TreeMap<>();
        Map<String, Integer> otherOwners = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (byte[] bytes : classes) {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if (node.version != 52) {
                problems.add(node.name + ": version " + node.version);
            }
            if (node.nestHostClass != null || node.nestMembers != null || node.permittedSubclasses != null
                    || node.recordComponents != null) {
                problems.add(node.name + ": a Java 11+ attribute");
            }
            StringWriter verifier = new StringWriter();
            CheckClassAdapter.verify(reader, Java8TargetTest.class.getClassLoader(), false, new PrintWriter(verifier));
            if (!verifier.toString().isEmpty()) {
                problems.add(node.name + ": " + verifier);
            }
            references(node, problems, jdkRefs, otherOwners);
        }

        String jdk8 = System.getProperty("neoskript.jdk8.home");
        List<String> missing = new ArrayList<>();
        if (jdk8 != null && !jdk8.isBlank()) {
            try (Jdk8Api api = new Jdk8Api(Path.of(jdk8.strip()))) {
                for (String reference : jdkRefs.keySet()) {
                    if (!api.has(reference)) {
                        missing.add(reference);
                    }
                }
            }
        }
        System.out.println("[java8] " + classes.size() + " classes, " + jdkRefs.size() + " distinct JDK references"
                + (jdk8 == null ? " (not checked: no neoskript.jdk8.home)" : ", checked against " + jdk8));
        jdkRefs.forEach((reference, count) -> System.out.println("[java8] jdk " + count + "x " + reference));
        otherOwners.forEach((owner, count) -> System.out.println("[java8] other " + count + "x " + owner));
        assertEquals(List.of(), problems);
        assertEquals(List.of(), missing, "Not in Java 8's API");
    }

    /**
     * The two targets' code, timed: a loop of arithmetic, variables and an effect (a constant of the class), run
     * enough to be compiled by C2. Prints the times; asserts nothing about them.
     */
    @Test
    void timesBothTargets() {
        String script = """
                on load:
                \tset {total} to 0
                \tset {_i} to 0
                \twhile {_i} is below 20000:
                \t\tset {_i} to {_i} + 1
                \t\tset {total} to {total} + {_i} * 2
                \t\tif 5 is below {total}:
                \t\t\tsend {total}
                """;
        Trigger modern = trigger(script);
        Trigger[] java8 = new Trigger[1];
        withTarget8(new ArrayList<>(), () -> java8[0] = trigger(script));
        for (int round = 0; round < 3; round++) {
            System.out.printf("[java8] round %d: java 21 %.2f ms, java 8 %.2f ms%n", round, time(modern),
                    time(java8[0]));
        }
        // Compiling and defining the corpus's classes: hidden classes, against a class loader for each class.
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            compileCorpus();
            long hidden = System.nanoTime() - start;
            List<byte[]> classes = new ArrayList<>();
            start = System.nanoTime();
            withTarget8(classes, Java8TargetTest::compileCorpus);
            long loaders = System.nanoTime() - start;
            System.out.printf("[java8] corpus round %d (%d classes): java 21 %.0f ms, java 8 %.0f ms%n", round,
                    classes.size(), hidden / 1e6, loaders / 1e6);
        }
    }

    /** Metaspace each compiled class takes, with both targets: a hidden class, against a class and its loader. */
    @Test
    void measuresMetaspacePerClass() {
        String script = "on load:\n\tset {_x} to 1 + 2\n\tsend {_x}\n";
        for (int round = 0; round < 2; round++) {
            long modern = metaspacePerClass(script, false);
            long java8 = metaspacePerClass(script, true);
            System.out.printf("[java8] metaspace round %d: java 21 %d bytes a class, java 8 %d bytes a class%n", round,
                    modern, java8);
        }
    }

    private static long metaspacePerClass(String script, boolean java8) {
        int count = 2000;
        List<Trigger> kept = new ArrayList<>();
        Runnable compile = () -> {
            for (int i = 0; i < count; i++) {
                kept.add(trigger(script));
            }
        };
        System.gc();
        long before = metaspaceUsed();
        if (java8) {
            withTarget8(new ArrayList<>(), compile);
        } else {
            compile.run();
        }
        System.gc();
        long used = metaspaceUsed() - before;
        assertEquals(count, kept.size());
        return used / count;
    }

    private static long metaspaceUsed() {
        long used = 0;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Metaspace")) {
                used += pool.getUsage().getUsed();
            }
        }
        return used;
    }

    private static void compileCorpus() {
        var library = CompilerFuzzTest.library();
        List<String> originals = ScriptFuzzer.fromRepository().originals();
        for (String text : originals) {
            List<ParsedScript> parsed = new ScriptLoader(library.registry()).load(List.of(new SourceFile("c.sk", text)));
            new ScriptCompiler(library, new VariableStore()).compile(parsed);
        }
    }

    private static double time(Trigger trigger) {
        for (int i = 0; i < 200; i++) {
            trigger.execute(new SimpleTriggerContext(null));
        }
        long start = System.nanoTime();
        int runs = 400;
        for (int i = 0; i < runs; i++) {
            trigger.execute(new SimpleTriggerContext(null));
        }
        return (System.nanoTime() - start) / 1e6 / runs;
    }

    private static Trigger trigger(String script) {
        var library = CompilerFuzzTest.library();
        library.condition("Below", info -> (context, args) -> ((Number) args[0]).doubleValue()
                < ((Number) args[1]).doubleValue(), "%number% is below %number%");
        List<ParsedScript> parsed = new ScriptLoader(library.registry()).load(List.of(new SourceFile("bench.sk", script)));
        VariableStore globals = new VariableStore();
        CompiledScripts compiled = new ScriptCompiler(library, globals).compile(parsed);
        assertTrue(parsed.getFirst().diagnostics().isEmpty(), parsed.getFirst().diagnostics().toString());
        assertTrue(compiled.diagnostics().isEmpty(), compiled.diagnostics().toString());
        Trigger trigger = compiled.triggers().getFirst().trigger();
        trigger.execute(new SimpleTriggerContext(null));
        if (script.contains("{total}")) {
            assertEquals(400020000L, ((Number) globals.get("total")).longValue());
        }
        return trigger;
    }

    private static void withTarget8(List<byte[]> classes, Runnable body) {
        String old = System.getProperty(TriggerCompiler.TARGET_PROPERTY);
        System.setProperty(TriggerCompiler.TARGET_PROPERTY, "8");
        TriggerCompiler.definedListener = classes::add;
        try {
            body.run();
        } finally {
            TriggerCompiler.definedListener = null;
            if (old == null) {
                System.clearProperty(TriggerCompiler.TARGET_PROPERTY);
            } else {
                System.setProperty(TriggerCompiler.TARGET_PROPERTY, old);
            }
        }
    }

    /** Collects what the class refers to: JDK members as {@code owner.name desc}, other owners by name. */
    private static void references(ClassNode node, List<String> problems, Map<String, Integer> jdk,
            Map<String, Integer> other) {
        Set<String> found = new TreeSet<>();
        type(Type.getObjectType(node.superName), found);
        node.interfaces.forEach(name -> type(Type.getObjectType(name), found));
        for (FieldNode field : node.fields) {
            type(Type.getType(field.desc), found);
        }
        for (MethodNode method : node.methods) {
            type(Type.getMethodType(method.desc), found);
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (block.type != null) {
                    type(Type.getObjectType(block.type), found);
                }
            }
            for (AbstractInsnNode insn : method.instructions) {
                switch (insn) {
                    case MethodInsnNode call -> {
                        type(Type.getObjectType(call.owner), found);
                        type(Type.getMethodType(call.desc), found);
                        found.add("M " + call.owner + "." + call.name + call.desc);
                    }
                    case FieldInsnNode field -> {
                        type(Type.getObjectType(field.owner), found);
                        type(Type.getType(field.desc), found);
                        found.add("F " + field.owner + "." + field.name + " " + field.desc);
                    }
                    case TypeInsnNode typed -> type(Type.getObjectType(typed.desc), found);
                    case MultiANewArrayInsnNode array -> type(Type.getType(array.desc), found);
                    case InvokeDynamicInsnNode indy -> problems.add(node.name + ": invokedynamic " + indy.name);
                    case LdcInsnNode ldc -> {
                        if (ldc.cst instanceof Type type) {
                            type(type, found);
                        } else if (ldc.cst instanceof ConstantDynamic || ldc.cst instanceof Handle) {
                            problems.add(node.name + ": ldc " + ldc.cst);
                        }
                    }
                    default -> {}
                }
            }
        }
        for (String reference : found) {
            String owner = reference.substring(2).split("[. ]", 2)[0];
            if (owner.startsWith("[")) {
                continue;
            }
            if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/")
                    || owner.startsWith("sun/")) {
                jdk.merge(reference, 1, Integer::sum);
            } else if (!owner.equals(node.name)) {
                other.merge(owner, 1, Integer::sum);
            }
        }
    }

    private static void type(Type type, Set<String> found) {
        switch (type.getSort()) {
            case Type.METHOD -> {
                type(type.getReturnType(), found);
                for (Type argument : type.getArgumentTypes()) {
                    type(argument, found);
                }
            }
            case Type.ARRAY -> type(type.getElementType(), found);
            case Type.OBJECT -> found.add("C " + type.getInternalName());
            default -> {}
        }
    }

    /** Java 8's API, from its rt.jar: whether a class, or a member a class has or inherits, is there. */
    private static final class Jdk8Api implements AutoCloseable {

        private final ZipFile rt;
        private final Map<String, ClassNode> classes = new HashMap<>();

        Jdk8Api(Path home) throws IOException {
            Path jar = home.resolve("jre/lib/rt.jar");
            if (!Files.exists(jar)) {
                jar = home.resolve("lib/rt.jar");
            }
            rt = new ZipFile(jar.toFile());
        }

        boolean has(String reference) {
            String kind = reference.substring(0, 1);
            String rest = reference.substring(2);
            if (kind.equals("C")) {
                return node(rest) != null;
            }
            int dot = rest.indexOf('.');
            String owner = rest.substring(0, dot);
            String member = rest.substring(dot + 1);
            return kind.equals("M") ? hasMethod(owner, member) : hasField(owner, member.replace(' ', ':'));
        }

        private boolean hasMethod(String owner, String nameAndDesc) {
            ClassNode node = node(owner);
            if (node == null) {
                return false;
            }
            for (MethodNode method : node.methods) {
                if ((method.name + method.desc).equals(nameAndDesc)) {
                    return true;
                }
            }
            if (node.superName != null && hasMethod(node.superName, nameAndDesc)) {
                return true;
            }
            for (String in : node.interfaces) {
                if (hasMethod(in, nameAndDesc)) {
                    return true;
                }
            }
            // Interfaces inherit Object's methods.
            return (node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0 && hasMethod("java/lang/Object", nameAndDesc);
        }

        private boolean hasField(String owner, String nameAndDesc) {
            ClassNode node = node(owner);
            if (node == null) {
                return false;
            }
            for (FieldNode field : node.fields) {
                if ((field.name + ":" + field.desc).equals(nameAndDesc)) {
                    return true;
                }
            }
            if (node.superName != null && hasField(node.superName, nameAndDesc)) {
                return true;
            }
            for (String in : node.interfaces) {
                if (hasField(in, nameAndDesc)) {
                    return true;
                }
            }
            return false;
        }

        private ClassNode node(String name) {
            return classes.computeIfAbsent(name, n -> {
                ZipEntry entry = rt.getEntry(n + ".class");
                if (entry == null) {
                    return null;
                }
                try (InputStream in = rt.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
                    return node;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        @Override
        public void close() throws IOException {
            rt.close();
        }
    }
}
