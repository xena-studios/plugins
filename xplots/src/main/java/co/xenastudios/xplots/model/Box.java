package co.xenastudios.xplots.model;

/** A box of blocks, corners included. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** The box with these two corners, in any order. */
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
            Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(int x, int y, int z) {
        return containsColumn(x, z) && y >= minY && y <= maxY;
    }

    /** Whether the column at x, z is in the box, at any height. */
    public boolean containsColumn(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Whether the two boxes share a column. */
    public boolean overlapsColumns(Box other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) width() * height() * depth();
    }

    /** "5×5", or "5×7": the size players see. */
    public String size() {
        return width() + "×" + depth();
    }
}
