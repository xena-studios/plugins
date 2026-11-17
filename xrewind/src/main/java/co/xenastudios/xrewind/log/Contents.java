package co.xenastudios.xrewind.log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.ItemStack;

/**
 * What a block held, kept with its break so a rollback brings it back whole: a
 * container's items, or a sign's text. Stored as bytes, one kind byte first.
 */
public sealed interface Contents {

    byte ITEMS = 'I';
    byte SIGN = 'S';

    /** A container's slots, copies, nulls for empty ones. */
    record Items(ItemStack[] items) implements Contents {}

    /** Both sides of a sign, four lines each, front first. */
    record SignText(List<Component> lines, boolean waxed) implements Contents {}

    /**
     * Whether blocks of this type can hold contents: worked out once for every block type,
     * so a listener can skip the rest without looking at the block's state.
     */
    static boolean canHold(org.bukkit.Material material) {
        return Holders.SET.contains(material);
    }

    /** Every block type whose state is a container or a sign. */
    final class Holders {
        static final java.util.Set<org.bukkit.Material> SET;

        static {
            java.util.Set<org.bukkit.Material> found = java.util.EnumSet.noneOf(org.bukkit.Material.class);
            for (org.bukkit.Material material : org.bukkit.Material.values()) {
                try {
                    if (!material.isLegacy() && material.isBlock()
                        && material.createBlockData().createBlockState() instanceof BlockState state
                        && (state instanceof Container || state instanceof Sign)) {
                        found.add(material);
                    }
                } catch (RuntimeException ignored) {
                    // A block with no state of its own.
                }
            }
            SET = java.util.Collections.unmodifiableSet(found);
        }

        private Holders() {}
    }

    /**
     * What the block holds now, or null if it holds nothing worth keeping. A snapshot:
     * call on the block's thread, before it changes.
     */
    static Contents of(Block block) {
        BlockState state = block.getState(false);
        if (state instanceof Container container) {
            ItemStack[] items = own(container).getContents();
            boolean any = false;
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null && !items[i].isEmpty()) {
                    items[i] = items[i].clone();
                    any = true;
                } else {
                    items[i] = null;
                }
            }
            return any ? new Items(items) : null;
        }
        if (state instanceof Sign sign) {
            List<Component> lines = new ArrayList<>(8);
            boolean any = false;
            for (Side side : new Side[] {Side.FRONT, Side.BACK}) {
                SignSide face = sign.getSide(side);
                for (Component line : face.lines()) {
                    lines.add(line);
                    any |= !line.equals(Component.empty());
                }
            }
            return any ? new SignText(lines, sign.isWaxed()) : null;
        }
        return null;
    }

    /** Puts the contents into the block, which must already be the right kind. On the block's thread. */
    static void restore(Block block, Contents contents) {
        BlockState state = block.getState(false);
        if (contents instanceof Items items && state instanceof Container container) {
            org.bukkit.inventory.Inventory inventory = own(container);
            ItemStack[] fit = java.util.Arrays.copyOf(items.items(), inventory.getSize());
            inventory.setContents(fit);
        } else if (contents instanceof SignText text && state instanceof Sign) {
            Sign sign = (Sign) block.getState();
            Side[] sides = {Side.FRONT, Side.BACK};
            for (int s = 0; s < 2; s++) {
                SignSide face = sign.getSide(sides[s]);
                for (int line = 0; line < 4 && s * 4 + line < text.lines().size(); line++) {
                    face.line(line, text.lines().get(s * 4 + line));
                }
            }
            sign.setWaxed(text.waxed());
            sign.update(true, false);
        }
    }

    /** Empties a container, so changing the block doesn't drop what it held. */
    static void clear(Block block) {
        if (block.getState(false) instanceof Container container) {
            own(container).clear();
        }
    }

    /**
     * The block's own slots. A chest beside another is one 54-slot inventory to players, but
     * each half is its own block: only this half's 27 slots are kept, put back or emptied.
     */
    private static org.bukkit.inventory.Inventory own(Container container) {
        return container instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : container.getInventory();
    }

    static byte[] write(Contents contents) {
        if (contents instanceof Items items) {
            byte[] body = ItemStack.serializeItemsAsBytes(items.items());
            byte[] out = new byte[body.length + 1];
            out[0] = ITEMS;
            System.arraycopy(body, 0, out, 1, body.length);
            return out;
        }
        SignText text = (SignText) contents;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(SIGN);
            out.writeBoolean(text.waxed());
            out.writeByte(text.lines().size());
            for (Component line : text.lines()) {
                out.writeUTF(GsonComponentSerializer.gson().serialize(line));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Reads what {@link #write} wrote, or null if it can't be read. */
    static Contents read(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            if (data[0] == ITEMS) {
                byte[] body = new byte[data.length - 1];
                System.arraycopy(data, 1, body, 0, body.length);
                return new Items(ItemStack.deserializeItemsFromBytes(body));
            }
            if (data[0] == SIGN) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1))) {
                    boolean waxed = in.readBoolean();
                    int count = in.readUnsignedByte();
                    List<Component> lines = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        lines.add(GsonComponentSerializer.gson().deserialize(in.readUTF()));
                    }
                    return new SignText(lines, waxed);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }
}
