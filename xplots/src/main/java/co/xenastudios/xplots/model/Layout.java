package co.xenastudios.xplots.model;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.BlockFace;

/**
 * How a selection splits into a grid of plots with paths between them, and where each
 * plot's sign goes ({@link SignPlacement}: the middle of the front edge, or a corner). Columns run along x and rows along z; the grid is centred in the
 * selection, and whatever's left over at the edges is path.
 *
 * <p>Rows face each other in pairs across a path, and each plot's sign stands on that
 * path, in the middle of the plot's edge, facing away from the plot. A path narrower
 * than two blocks has no room for two rows' signs, so then each stands just inside its
 * plot's edge.
 */
public record Layout(Box area, int sizeX, int sizeZ, int path, int originX, int originZ, int cols, int rows) {

    /** A plot as the layout places it, before it's made. */
    public record Spot(int number, Box box, int signX, int signY, int signZ, BlockFace facing) {}

    /** Splits the selection, or returns null if not even one plot fits. */
    public static Layout of(Box area, int sizeX, int sizeZ, int path) {
        int cols = (area.width() + path) / (sizeX + path);
        int rows = (area.depth() + path) / (sizeZ + path);
        if (cols < 1 || rows < 1) {
            return null;
        }
        int usedX = cols * (sizeX + path) - path;
        int usedZ = rows * (sizeZ + path) - path;
        return new Layout(area, sizeX, sizeZ, path,
            area.minX() + (area.width() - usedX) / 2, area.minZ() + (area.depth() - usedZ) / 2, cols, rows);
    }

    public int count() {
        return cols * rows;
    }

    /**
     * The plot number at a column, numbered from 1 across each row, or 0 for a path. Pure
     * arithmetic, so it costs the same with ten plots or ten thousand.
     */
    public int numberAt(int x, int z) {
        int dx = x - originX;
        int dz = z - originZ;
        if (dx < 0 || dz < 0) {
            return 0;
        }
        int col = dx / (sizeX + path);
        int row = dz / (sizeZ + path);
        if (col >= cols || row >= rows || dx % (sizeX + path) >= sizeX || dz % (sizeZ + path) >= sizeZ) {
            return 0;
        }
        return row * cols + col + 1;
    }

    /** Every plot, in number order, with signs placed as {@code signs} says. */
    public List<Spot> spots(SignPlacement signs) {
        List<Spot> spots = new ArrayList<>(count());
        boolean onPath = path >= 2;
        for (int row = 0; row < rows; row++) {
            // Even rows face the next row, odd rows the one before; a last row on its own
            // faces back toward the rest.
            boolean south = row % 2 == 0 && (row + 1 < rows || row == 0);
            for (int col = 0; col < cols; col++) {
                int minX = originX + col * (sizeX + path);
                int minZ = originZ + row * (sizeZ + path);
                Box box = new Box(minX, area.minY(), minZ, minX + sizeX - 1, area.maxY(), minZ + sizeZ - 1);
                SignPlacement.Spot sign = signs.place(box, south ? BlockFace.SOUTH : BlockFace.NORTH, onPath);
                int signY = Math.min(area.minY() + 1, area.maxY());
                spots.add(new Spot(row * cols + col + 1, box, sign.x(), signY, sign.z(), sign.facing()));
            }
        }
        return spots;
    }
}
