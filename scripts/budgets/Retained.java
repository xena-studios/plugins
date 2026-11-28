import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The heap a plugin's class loader alone keeps alive, from a heap dump: scripts/budgets/check.sh's
 * heap budget (gradle/perf-budgets.txt, heap-mb).
 *
 *   java Retained.java <dump.hprof> <main class>...
 *
 * For each main class (plugin.yml's main:), its class loader, every class that loader defined and
 * every instance of those classes are the plugin's. What the plugin retains is everything reachable
 * from the GC roots that is no longer reachable once those are taken away: its own objects, the
 * libraries and data only it holds, its classes' mirrors and statics, and on Paper the libraries:
 * loader and the Kotlin stdlib it holds alone. What the server keeps for any plugin (its entry in
 * the plugin manager, the RegisteredListener and PluginCommand around the plugin's own objects)
 * and the server's own variance from boot to boot aren't in it, so the same jar reads the same.
 *
 * Sizes are the JVM's with compressed oops and class pointers (a 1 GB heap, as check.sh boots):
 * a 12-byte header, 4-byte references, 8-byte alignment. Weak, phantom and finalizer references
 * aren't followed, since they keep nothing alive; soft ones are, since a server with a full GB
 * keeps them.
 *
 * Prints JSON: {"<main class>": {"bytes": N, "objects": N, "top": [[class, bytes, objects], ...]}}.
 * Exit 2 when a main class isn't in the dump.
 */
public class Retained {
    static final long CHUNK = 1L << 30;
    static ByteBuffer[] chunks;
    static long fileSize;
    static int idSize;

    static int u1(long p) { return chunks[(int) (p / CHUNK)].get((int) (p % CHUNK)) & 0xff; }
    static int u2(long p) { return chunks[(int) (p / CHUNK)].getShort((int) (p % CHUNK)) & 0xffff; }
    static int u4(long p) { return chunks[(int) (p / CHUNK)].getInt((int) (p % CHUNK)); }
    static long u8(long p) { return chunks[(int) (p / CHUNK)].getLong((int) (p % CHUNK)); }
    static long id(long p) { return idSize == 8 ? u8(p) : u4(p) & 0xffffffffL; }

    /** A basic type's size in the dump (references are idSize there). */
    static int dumpSize(int type) {
        return switch (type) {
            case 2 -> idSize;
            case 4, 8 -> 1;
            case 5, 9 -> 2;
            case 6, 10 -> 4;
            case 7, 11 -> 8;
            default -> throw new IllegalStateException("basic type " + type);
        };
    }

    /** The same in the JVM's heap, with compressed oops. */
    static int heapSize(int type) { return type == 2 ? 4 : dumpSize(type); }

    static long align(long n) { return (n + 7) & ~7L; }

    // ---- objects: an open-addressing map from object id to index ----
    static long[] keys = new long[1 << 23];
    static int[] vals = new int[1 << 23];
    static int count;
    static long[] offset = new long[1 << 22]; // where its record's id starts
    static byte[] kind = new byte[1 << 22];   // 0 class, 1 instance, 2 object array, 3 primitive array

    static int slot(long k, long[] ks) { return (int) ((k * 0x9E3779B97F4A7C15L) >>> 40) & (ks.length - 1); }

    static int index(long k) {
        if (k == 0) return -1;
        int h = slot(k, keys);
        while (keys[h] != 0) {
            if (keys[h] == k) return vals[h];
            h = (h + 1) & (keys.length - 1);
        }
        return -1;
    }

    static void put(long k, int v) {
        if ((long) count * 2 > keys.length) {
            long[] ok = keys;
            int[] ov = vals;
            keys = new long[ok.length * 2];
            vals = new int[ok.length * 2];
            for (int i = 0; i < ok.length; i++) {
                if (ok[i] == 0) continue;
                int h = slot(ok[i], keys);
                while (keys[h] != 0) h = (h + 1) & (keys.length - 1);
                keys[h] = ok[i];
                vals[h] = ov[i];
            }
        }
        int h = slot(k, keys);
        while (keys[h] != 0) h = (h + 1) & (keys.length - 1);
        keys[h] = k;
        vals[h] = v;
    }

    static void add(long objectId, long p, int k) {
        if (count == offset.length) {
            offset = Arrays.copyOf(offset, count * 2);
            kind = Arrays.copyOf(kind, count * 2);
        }
        offset[count] = p;
        kind[count] = (byte) k;
        put(objectId, count++);
    }

    // ---- classes ----
    static final class Cls {
        long id, superId, loader;
        String name = "?";
        int[] fieldTypes;
        long[] fieldNames;
        long[] statics;     // references held by static fields
        long staticBytes;   // the static fields' size in the heap
        int[] refOffsets;   // where an instance's followed references are, in its dump record
        long shallow;       // an instance's size in the heap
    }

    static final Map<Long, String> strings = new HashMap<>();
    static final Map<Long, Long> classNameIds = new HashMap<>();
    static final Map<Long, Cls> classes = new HashMap<>();
    static final Map<Long, List<Long>> definedBy = new HashMap<>(); // loader -> its classes
    static long[] roots = new long[1 << 14];
    static int rootCount;
    static long[][] defines; // per object: the classes it defined, when it's a class loader, else null
    static int[] classOf; // per object: its class's object index (instances and object arrays), else -1
    static Cls[] clsAt;   // per object of kind 0: its Cls
    static long classMirror; // a java.lang.Class instance's own fields

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java Retained.java <dump.hprof> <main class>...");
            System.exit(2);
        }
        read(Path.of(args[0]));
        link();
        boolean[] all = reach(null);
        StringBuilder json = new StringBuilder("{");
        for (int a = 1; a < args.length; a++) {
            String main = args[a];
            List<Long> loaders = new ArrayList<>();
            for (Cls c : classes.values()) if (c.name.equals(main) && c.loader != 0) loaders.add(c.loader);
            if (loaders.isEmpty()) {
                System.err.println("no class " + main + " with a class loader in " + args[0]);
                System.exit(2);
            }
            boolean[] start = new boolean[count];
            for (long l : loaders) {
                mark(start, l);
                for (long c : definedBy.getOrDefault(l, List.of())) mark(start, c);
            }
            for (int i = 0; i < count; i++) {
                if (kind[i] == 1 && classOf[i] >= 0 && start[classOf[i]]) start[i] = true;
            }
            boolean[] without = reach(start);
            long bytes = 0, objects = 0;
            Map<String, long[]> byClass = new HashMap<>();
            for (int i = 0; i < count; i++) {
                if (!all[i] || without[i]) continue;
                long s = shallow(i);
                bytes += s;
                objects++;
                long[] t = byClass.computeIfAbsent(typeName(i), k -> new long[2]);
                t[0] += s;
                t[1]++;
            }
            if (a > 1) json.append(",");
            json.append("\n  \"").append(esc(main)).append("\": {\"bytes\": ").append(bytes)
                .append(", \"objects\": ").append(objects).append(", \"top\": [");
            List<Map.Entry<String, long[]>> top = new ArrayList<>(byClass.entrySet());
            top.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
            for (int t = 0; t < Math.min(15, top.size()); t++) {
                Map.Entry<String, long[]> e = top.get(t);
                json.append(t > 0 ? ", " : "").append("[\"").append(esc(e.getKey())).append("\", ")
                    .append(e.getValue()[0]).append(", ").append(e.getValue()[1]).append("]");
            }
            json.append("]}");
        }
        System.out.println(json.append("\n}"));
    }

    static void mark(boolean[] set, long objectId) {
        int i = index(objectId);
        if (i >= 0) set[i] = true;
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static void read(Path path) throws IOException {
        try (FileChannel fc = FileChannel.open(path)) {
            fileSize = fc.size();
            chunks = new ByteBuffer[(int) (fileSize / CHUNK) + 1];
            // Each chunk overlaps the next by 64 bytes, so a read of up to 8 bytes never spans two.
            for (int i = 0; i < chunks.length; i++) {
                chunks[i] = fc.map(FileChannel.MapMode.READ_ONLY, i * CHUNK, Math.min(CHUNK + 64, fileSize - i * CHUNK));
            }
        }
        long p = 0;
        while (u1(p) != 0) p++;
        p++;
        idSize = u4(p);
        p += 12;
        while (p < fileSize) {
            int tag = u1(p);
            long len = u4(p + 5) & 0xffffffffL;
            long body = p + 9;
            p = body + len;
            switch (tag) {
                case 0x01 -> {
                    byte[] s = new byte[(int) (len - idSize)];
                    for (int i = 0; i < s.length; i++) s[i] = (byte) u1(body + idSize + i);
                    strings.put(id(body), new String(s, StandardCharsets.UTF_8));
                }
                case 0x02 -> classNameIds.put(id(body + 4), id(body + 8 + idSize));
                case 0x0C, 0x1C -> heap(body, body + len);
                default -> { }
            }
        }
    }

    static void root(long objectId) {
        if (rootCount == roots.length) roots = Arrays.copyOf(roots, rootCount * 2);
        roots[rootCount++] = objectId;
    }

    static void heap(long p, long end) {
        while (p < end) {
            int t = u1(p++);
            switch (t) {
                case 0xFF, 0x05, 0x07 -> { root(id(p)); p += idSize; }
                case 0x01 -> { root(id(p)); p += 2L * idSize; }
                case 0x02, 0x03, 0x08 -> { root(id(p)); p += idSize + 8; }
                case 0x04, 0x06 -> { root(id(p)); p += idSize + 4; }
                case 0x20 -> {
                    Cls c = new Cls();
                    long start = p;
                    c.id = id(p);
                    long q = p + idSize + 4;
                    c.superId = id(q);
                    c.loader = id(q + idSize);
                    q += 6L * idSize + 4; // super, loader, signers, protection domain, two reserved, instance size
                    int constants = u2(q);
                    q += 2;
                    for (int i = 0; i < constants; i++) q += 3 + dumpSize(u1(q + 2));
                    int staticCount = u2(q);
                    q += 2;
                    List<Long> refs = new ArrayList<>();
                    for (int i = 0; i < staticCount; i++) {
                        int type = u1(q + idSize);
                        if (type == 2) refs.add(id(q + idSize + 1));
                        c.staticBytes += heapSize(type);
                        q += idSize + 1 + dumpSize(type);
                    }
                    c.statics = refs.stream().mapToLong(Long::longValue).toArray();
                    int fieldCount = u2(q);
                    q += 2;
                    c.fieldTypes = new int[fieldCount];
                    c.fieldNames = new long[fieldCount];
                    for (int i = 0; i < fieldCount; i++) {
                        c.fieldNames[i] = id(q);
                        c.fieldTypes[i] = u1(q + idSize);
                        q += idSize + 1;
                    }
                    classes.put(c.id, c);
                    add(c.id, start, 0);
                    p = q;
                }
                case 0x21 -> { add(id(p), p, 1); p += 2L * idSize + 8 + (u4(p + 2L * idSize + 4) & 0xffffffffL); }
                case 0x22 -> { add(id(p), p, 2); p += 2L * idSize + 8 + (long) u4(p + idSize + 4) * idSize; }
                case 0x23 -> { add(id(p), p, 3); p += idSize + 9 + (long) u4(p + idSize + 4) * dumpSize(u1(p + idSize + 8)); }
                default -> throw new IllegalStateException("heap record " + Integer.toHexString(t) + " at " + p);
            }
        }
    }

    static void link() {
        for (Cls c : classes.values()) {
            Long nameId = classNameIds.get(c.id);
            if (nameId != null) c.name = strings.getOrDefault(nameId, "?").replace('/', '.');
            if (c.loader != 0) definedBy.computeIfAbsent(c.loader, k -> new ArrayList<>()).add(c.id);
        }
        // The referent of a weak, phantom or finalizer reference keeps nothing alive.
        long referent = 0;
        Cls reference = byName("java.lang.ref.Reference");
        if (reference != null) {
            for (long n : reference.fieldNames) if ("referent".equals(strings.get(n))) referent = n;
        }
        for (Cls c : classes.values()) {
            boolean weak = false;
            for (Cls k = c; k != null; k = classes.get(k.superId)) {
                if (k.name.equals("java.lang.ref.WeakReference") || k.name.equals("java.lang.ref.PhantomReference")
                    || k.name.equals("java.lang.ref.FinalReference")) weak = true;
            }
            List<Integer> refs = new ArrayList<>();
            long at = 0, fields = 0;
            for (Cls k = c; k != null; k = classes.get(k.superId)) {
                for (int f = 0; f < k.fieldTypes.length; f++) {
                    int type = k.fieldTypes[f];
                    if (type == 2 && !(weak && k == reference && k.fieldNames[f] == referent)) refs.add((int) at);
                    at += dumpSize(type);
                    fields += heapSize(type);
                }
            }
            c.refOffsets = refs.stream().mapToInt(Integer::intValue).toArray();
            c.shallow = align(12 + fields);
        }
        Cls mirror = byName("java.lang.Class");
        classMirror = mirror == null ? 96 : mirror.shallow;
        classOf = new int[count];
        clsAt = new Cls[count];
        defines = new long[count][];
        for (Map.Entry<Long, List<Long>> e : definedBy.entrySet()) {
            int i = index(e.getKey());
            if (i >= 0) defines[i] = e.getValue().stream().mapToLong(Long::longValue).toArray();
        }
        for (int i = 0; i < count; i++) {
            long p = offset[i];
            classOf[i] = switch (kind[i]) {
                case 1 -> index(id(p + idSize + 4));
                case 2 -> index(id(p + idSize + 8));
                default -> -1;
            };
            if (kind[i] == 0) clsAt[i] = classes.get(id(p));
        }
    }

    static Cls byName(String name) {
        for (Cls c : classes.values()) if (c.name.equals(name)) return c;
        return null;
    }

    static String typeName(int i) {
        return switch (kind[i]) {
            case 0 -> "java.lang.Class";
            case 1, 2 -> classOf[i] >= 0 ? clsAt[classOf[i]].name : "?";
            default -> switch (u1(offset[i] + idSize + 8)) {
                case 4 -> "boolean[]";
                case 5 -> "char[]";
                case 6 -> "float[]";
                case 7 -> "double[]";
                case 8 -> "byte[]";
                case 9 -> "short[]";
                case 10 -> "int[]";
                default -> "long[]";
            };
        };
    }

    static long shallow(int i) {
        long p = offset[i];
        return switch (kind[i]) {
            case 0 -> align(classMirror + clsAt[i].staticBytes);
            case 1 -> classOf[i] >= 0 ? clsAt[classOf[i]].shallow : align(12 + (u4(p + 2L * idSize + 4) & 0xffffffffL));
            case 2 -> align(16 + 4L * u4(p + idSize + 4));
            default -> align(16 + (long) u4(p + idSize + 4) * dumpSize(u1(p + idSize + 8)));
        };
    }

    /** Every object an object keeps alive directly. */
    static void refs(int i, IntConsumer f) {
        long p = offset[i];
        switch (kind[i]) {
            case 0 -> {
                Cls c = clsAt[i];
                for (long r : c.statics) emit(r, f);
                emit(c.superId, f);
                emit(c.loader, f);
            }
            case 1 -> {
                if (classOf[i] < 0) return;
                f.accept(classOf[i]);
                Cls c = clsAt[classOf[i]];
                long fields = p + 2L * idSize + 8;
                for (int at : c.refOffsets) emit(id(fields + at), f);
                // A class loader keeps every class it defined, hidden ones too (the JVM's class loader data).
                if (defines[i] != null) for (long d : defines[i]) emit(d, f);
            }
            case 2 -> {
                if (classOf[i] >= 0) f.accept(classOf[i]);
                int len = u4(p + idSize + 4);
                long q = p + 2L * idSize + 8;
                for (int j = 0; j < len; j++) emit(id(q + (long) j * idSize), f);
            }
            default -> { }
        }
    }

    static void emit(long r, IntConsumer f) {
        int j = index(r);
        if (j >= 0) f.accept(j);
    }

    /** What the roots reach, never entering a blocked object. */
    static boolean[] reach(boolean[] blocked) {
        boolean[] seen = new boolean[count];
        int[] stack = new int[count];
        int[] top = {0};
        IntConsumer visit = j -> {
            if (!seen[j] && (blocked == null || !blocked[j])) {
                seen[j] = true;
                stack[top[0]++] = j;
            }
        };
        for (int r = 0; r < rootCount; r++) emit(roots[r], visit);
        while (top[0] > 0) refs(stack[--top[0]], visit);
        return seen;
    }
}
