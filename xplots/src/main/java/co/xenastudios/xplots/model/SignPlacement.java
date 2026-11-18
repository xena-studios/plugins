package co.xenastudios.xplots.model;

import java.util.Locale;
import java.util.Map;
import org.bukkit.block.BlockFace;

/**
 * Where on its plot's front edge a sign stands, and which way it faces. "Left" and
 * "right" are as seen from the walkway, looking at the plot.
 */
public record SignPlacement(Position position, Rotation rotation) {

    public enum Position { MIDDLE, LEFT_CORNER, RIGHT_CORNER }

    /** {@code STRAIGHT} faces the walkway; {@code DIAGONAL} is turned 45° out from its corner. */
    public enum Rotation { STRAIGHT, DIAGONAL }

    /** The 16 ways a standing sign can face, clockwise from north. */
    public static final BlockFace[] FACINGS = {
        BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
        BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
        BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
        BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST};

    /** The defaults with a field's own {@code sign-position} and {@code sign-rotation} on top. */
    public static SignPlacement of(SignPlacement defaults, Map<String, String> overrides) {
        Position position = parse(Position.class, overrides.get("sign-position"), defaults.position());
        Rotation rotation = parse(Rotation.class, overrides.get("sign-rotation"), defaults.rotation());
        return new SignPlacement(position, rotation);
    }

    /** {@code left-corner}, as written in the config, or the fallback if it isn't one. */
    public static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Where the sign goes for a plot whose front faces {@code front} (north or south): its
     * x, its z, and which way it faces. On the walkway just outside the plot when
     * {@code onPath}, or just inside its edge when the walkway is too narrow.
     */
    public Spot place(Box box, BlockFace front, boolean onPath) {
        boolean south = front == BlockFace.SOUTH;
        int z = south ? (onPath ? box.maxZ() + 1 : box.maxZ()) : (onPath ? box.minZ() - 1 : box.minZ());
        // Facing the plot from its walkway, left is west for a south-facing front, east for a north-facing one.
        int left = south ? box.minX() : box.maxX();
        int right = south ? box.maxX() : box.minX();
        int x = switch (position) {
            case MIDDLE -> box.minX() + (box.width() - 1) / 2;
            case LEFT_CORNER -> left;
            case RIGHT_CORNER -> right;
        };
        BlockFace facing = front;
        if (rotation == Rotation.DIAGONAL && position != Position.MIDDLE) {
            boolean towardWest = position == Position.LEFT_CORNER ? south : !south;
            facing = south ? (towardWest ? BlockFace.SOUTH_WEST : BlockFace.SOUTH_EAST)
                : (towardWest ? BlockFace.NORTH_WEST : BlockFace.NORTH_EAST);
        }
        return new Spot(x, z, facing);
    }

    /** A sign's column and facing. */
    public record Spot(int x, int z, BlockFace facing) {}

    /** The facing a quarter-turn's half (45°) clockwise from this one. */
    public static BlockFace turn(BlockFace facing) {
        for (int i = 0; i < FACINGS.length; i++) {
            if (FACINGS[i] == facing) {
                return FACINGS[(i + 2) % FACINGS.length];
            }
        }
        return BlockFace.SOUTH;
    }

    /** The facing that looks back at a player whose view has this yaw, to the nearest 22.5°. */
    public static BlockFace toward(float yaw) {
        // Yaw 0 looks south; a sign facing the player then faces north, index 0.
        int index = Math.floorMod(Math.round(yaw / 22.5f), 16);
        return FACINGS[index];
    }
}
