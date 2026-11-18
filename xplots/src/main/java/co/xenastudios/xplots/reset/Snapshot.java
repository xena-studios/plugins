package co.xenastudios.xplots.reset;

import co.xenastudios.xplots.model.Box;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * A plot's blocks, as stored: a palette of the block states it has, then one palette
 * index per block (y, then z, then x), deflated. A 5×5 plot ten blocks high is a few
 * hundred bytes.
 *
 * @param palette block states as Minecraft writes them, such as {@code minecraft:oak_stairs[facing=north]}
 */
public record Snapshot(int width, int height, int depth, List<String> palette, int[] indices) {

    private static final int VERSION = 1;

    /** Reads the box's blocks. Main thread, with its chunks loaded. */
    public static Snapshot capture(World world, Box box) {
        int[] indices = new int[Math.toIntExact(box.volume())];
        Map<BlockData, Integer> ids = new HashMap<>();
        List<String> palette = new ArrayList<>();
        int i = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockData data = world.getBlockData(x, y, z);
                    Integer id = ids.get(data);
                    if (id == null) {
                        id = palette.size();
                        ids.put(data, id);
                        palette.add(data.getAsString());
                    }
                    indices[i++] = id;
                }
            }
        }
        return new Snapshot(box.width(), box.height(), box.depth(), palette, indices);
    }

    public byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeByte(VERSION);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(depth);
            writeVarInt(out, palette.size());
            for (String state : palette) {
                out.writeUTF(state);
            }
            for (int index : indices) {
                writeVarInt(out, index);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e); // Writing to memory can't fail.
        }
        return bytes.toByteArray();
    }

    /** @throws IOException if the bytes aren't a snapshot */
    public static Snapshot decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new java.io.ByteArrayInputStream(data)))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("snapshot version " + version + " is newer than this xPlots");
            }
            int width = in.readInt();
            int height = in.readInt();
            int depth = in.readInt();
            long volume = (long) width * height * depth;
            if (width < 1 || height < 1 || depth < 1 || volume > 16_000_000) {
                throw new IOException("snapshot size " + width + "×" + height + "×" + depth + " is impossible");
            }
            int size = readVarInt(in);
            List<String> palette = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                palette.add(in.readUTF());
            }
            int[] indices = new int[(int) volume];
            for (int i = 0; i < indices.length; i++) {
                int index = readVarInt(in);
                if (index < 0 || index >= size) {
                    throw new IOException("snapshot points past its palette");
                }
                indices[i] = index;
            }
            return new Snapshot(width, height, depth, palette, indices);
        }
    }

    /**
     * The palette as block states this server knows. One it can't read (from a Minecraft
     * version that renamed it) is null, and those blocks are left as they are.
     */
    public BlockData[] states() {
        BlockData[] states = new BlockData[palette.size()];
        for (int i = 0; i < states.length; i++) {
            try {
                states[i] = Bukkit.createBlockData(palette.get(i));
            } catch (IllegalArgumentException e) {
                states[i] = null;
            }
        }
        return states;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("snapshot number too long");
    }
}
