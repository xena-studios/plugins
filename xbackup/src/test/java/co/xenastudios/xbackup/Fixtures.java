package co.xenastudios.xbackup;

import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.RegionLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test helpers: fake servers with real-format region files, and a backend that fails on cue. */
public final class Fixtures {

    private Fixtures() {}

    /** A region file in Minecraft's format: each chunk's record packed into sectors in order. */
    public static final class Region {
        public final TreeMap<Integer, byte[]> payloads = new TreeMap<>();
        public final int[] timestamps = new int[RegionLayout.CHUNKS];

        /** Random chunks, as if generated. */
        public static Region random(Random random, int chunks, int baseTime) {
            Region region = new Region();
            for (int n = 0; n < chunks; n++) {
                int index = random.nextInt(RegionLayout.CHUNKS);
                region.payloads.put(index, randomPayload(random));
                region.timestamps[index] = baseTime + random.nextInt(1000);
            }
            return region;
        }

        public static byte[] randomPayload(Random random) {
            byte[] payload = new byte[200 + random.nextInt(9000)];
            random.nextBytes(payload);
            return payload;
        }

        /** Changes a chunk, as the server writing it again would. */
        public void touch(Random random, int index, int time) {
            payloads.put(index, randomPayload(random));
            timestamps[index] = time;
        }

        /**
         * Writes changed chunks the way the server does: each to new sectors at the end of the
         * file, then the header pointed at them. The old sectors are zeroed (the server leaves
         * them as they were; a restore brings them back as zeros, which these tests compare).
         */
        public static void rewrite(Path file, java.util.Set<Integer> changed, Random random, int time) throws IOException {
            byte[] old = Files.readAllBytes(file);
            ByteBuffer header = ByteBuffer.wrap(old);
            int sector = old.length / 4096;
            java.io.ByteArrayOutputStream tail = new java.io.ByteArrayOutputStream();
            for (int index : changed) {
                int location = header.getInt(index * 4);
                if (location != 0) {
                    int start = (location >>> 8) * 4096;
                    java.util.Arrays.fill(old, start, start + (location & 0xFF) * 4096, (byte) 0);
                }
                byte[] payload = randomPayload(random);
                int length = payload.length + 1;
                int sectors = (length + 4 + 4095) / 4096;
                ByteBuffer record = ByteBuffer.allocate(sectors * 4096);
                record.putInt(length).put((byte) 2).put(payload);
                tail.write(record.array());
                header.putInt(index * 4, sector << 8 | sectors);
                header.putInt(4096 + index * 4, time);
                sector += sectors;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(old);
            out.write(tail.toByteArray());
            Files.write(file, out.toByteArray());
        }

        /**
         * Writes the file. With {@code shuffle}, chunks go in a random sector order, as a file
         * the server has rewritten many times looks.
         */
        public void write(Path file, Random shuffle) throws IOException {
            List<Integer> order = new ArrayList<>(payloads.keySet());
            if (shuffle != null) {
                java.util.Collections.shuffle(order, shuffle);
            }
            int[] location = new int[RegionLayout.CHUNKS];
            int sector = 2;
            ByteBuffer body = ByteBuffer.allocate(payloads.values().stream().mapToInt(p -> p.length + 5 + 4096).sum() + 8192);
            for (int index : order) {
                byte[] payload = payloads.get(index);
                int length = payload.length + 1;
                int sectors = (length + 4 + 4095) / 4096;
                location[index] = sector << 8 | sectors;
                body.position((sector - 2) * 4096);
                body.putInt(length).put((byte) 2).put(payload);
                sector += sectors;
            }
            ByteBuffer out = ByteBuffer.allocate(sector * 4096);
            for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                out.putInt(location[i]);
            }
            for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                out.putInt(timestamps[i]);
            }
            out.put(body.array(), 0, (sector - 2) * 4096);
            Files.createDirectories(file.getParent());
            Files.write(file, out.array());
        }
    }

    /** A small server: a world with regions in the 26.x layout, a nether, plugins and configs. */
    public static void server(Path root, Random random) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("server.properties"), "level-name=world\nmotd=test\n");
        Files.writeString(root.resolve("bukkit.yml"), "settings:\n  allow-end: true\n");
        Path world = root.resolve("world");
        Files.createDirectories(world);
        bytes(world.resolve("level.dat"), random, 3000);
        Files.writeString(world.resolve("session.lock"), "lock");
        for (int x = 0; x < 3; x++) {
            Region.random(random, 60, 1_700_000_000).write(
                world.resolve("dimensions/minecraft/overworld/region/r." + x + ".0.mca"), random);
        }
        Region.random(random, 10, 1_700_000_000).write(world.resolve("dimensions/minecraft/overworld/entities/r.0.0.mca"), null);
        bytes(world.resolve("playerdata/069a79f4-44e9-4726-a5be-fca90e38aaf5.dat"), random, 2000);
        Path nether = root.resolve("world_nether");
        Region.random(random, 20, 1_700_000_000).write(nether.resolve("DIM-1/region/r.0.0.mca"), null);
        bytes(nether.resolve("level.dat"), random, 1000);
        Files.createDirectories(root.resolve("plugins/Other"));
        Files.writeString(root.resolve("plugins/Other/config.yml"), "a: 1\n");
        bytes(root.resolve("plugins/Other/big.bin"), random, 3 << 20);
        bytes(root.resolve("plugins/other.jar"), random, 50_000);
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("logs/latest.log"), "log\n");
        // An empty region file, as the server sometimes leaves.
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/poi"));
        Files.write(world.resolve("dimensions/minecraft/overworld/poi/r.0.0.mca"), new byte[0]);
    }

    public static void bytes(Path file, Random random, int size) throws IOException {
        byte[] data = new byte[size];
        random.nextBytes(data);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    /** Every file under a folder and its bytes, by relative path, for comparing trees. */
    public static Map<String, byte[]> contents(Path root, java.util.function.Predicate<String> include) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.exists(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (include.test(path)) {
                    files.put(path, Files.readAllBytes(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** Overwrites part of a file in place, as corruption would. */
    public static void corrupt(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            one.flip();
            byte b = one.get();
            channel.write(ByteBuffer.wrap(new byte[] {(byte) (b ^ 0x5A)}), offset);
        }
    }

    /**
     * A folder backend that dies after a number of writes: that write and every call after it
     * fail, as if the process had been killed, so no clean-up code gets to run either.
     */
    public static final class Dying implements Backend {
        public final LocalBackend inner;
        private final AtomicInteger writesLeft;
        private final AtomicInteger written = new AtomicInteger();
        public volatile boolean dead;

        public Dying(LocalBackend inner, int writes) {
            this.inner = inner;
            this.writesLeft = new AtomicInteger(writes);
        }

        /** How many writes went through. */
        public int written() {
            return written.get();
        }

        private void write() throws IOException {
            if (dead || writesLeft.decrementAndGet() < 0) {
                dead = true;
                throw new IOException("killed");
            }
            written.incrementAndGet();
        }

        private void alive() throws IOException {
            if (dead) {
                throw new IOException("killed");
            }
        }

        @Override
        public String describe() {
            return "dying " + inner.describe();
        }

        @Override
        public void put(String name, Path file) throws IOException {
            write();
            inner.put(name, file);
        }

        @Override
        public void put(String name, byte[] data) throws IOException {
            write();
            inner.put(name, data);
        }

        @Override
        public InputStream get(String name) throws IOException {
            alive();
            return inner.get(name);
        }

        @Override
        public byte[] read(String name, long offset, int length) throws IOException {
            alive();
            return inner.read(name, offset, length);
        }

        @Override
        public List<FileInfo> list(String prefix) throws IOException {
            alive();
            return inner.list(prefix);
        }

        @Override
        public void delete(String name) throws IOException {
            write();
            inner.delete(name);
        }
    }
}
