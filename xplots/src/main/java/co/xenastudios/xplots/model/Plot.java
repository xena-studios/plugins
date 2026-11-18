package co.xenastudios.xplots.model;

import org.bukkit.block.BlockFace;

/** One plot: where it is, where its sign is, and who rents it. Main thread only. */
public final class Plot {

    private final Field field;
    private final int number;
    private final Box box;
    private int signX;
    private int signY;
    private int signZ;
    private BlockFace signFacing;
    private Lease lease;
    // What its sign last showed, so the sign is only rewritten when that changes.
    private String shown;

    public Plot(Field field, int number, Box box, int signX, int signY, int signZ, BlockFace signFacing) {
        this.field = field;
        this.number = number;
        this.box = box;
        this.signX = signX;
        this.signY = signY;
        this.signZ = signZ;
        this.signFacing = signFacing;
    }

    public Field field() {
        return field;
    }

    public int number() {
        return number;
    }

    /** {@code box-12}: the field's name and the plot's number. */
    public String id() {
        return field.name() + "-" + number;
    }

    public Box box() {
        return box;
    }

    public int signX() {
        return signX;
    }

    public int signY() {
        return signY;
    }

    public int signZ() {
        return signZ;
    }

    /** The way the sign's text faces. */
    public BlockFace signFacing() {
        return signFacing;
    }

    public void moveSign(int x, int y, int z, BlockFace facing) {
        signX = x;
        signY = y;
        signZ = z;
        signFacing = facing;
        shown = null;
    }

    /** The rental, or null if the plot is free. */
    public Lease lease() {
        return lease;
    }

    public void lease(Lease lease) {
        this.lease = lease;
    }

    public boolean isFree() {
        return lease == null;
    }

    public String shown() {
        return shown;
    }

    public void shown(String shown) {
        this.shown = shown;
    }

    @Override
    public String toString() {
        return id();
    }
}
