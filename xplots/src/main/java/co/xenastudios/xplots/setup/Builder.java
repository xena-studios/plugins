package co.xenastudios.xplots.setup;

import co.xenastudios.xplots.XPlots;
import co.xenastudios.xplots.model.Box;
import co.xenastudios.xplots.model.Field;
import co.xenastudios.xplots.model.Layout;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.SignPlacement;
import java.util.List;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/** Makes and deletes fields and plots: in memory, in the database, in the world. Main thread. */
public final class Builder {

    /** Field names: lowercase letters, digits and _, so plot ids like {@code box-12} read back. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,24}");
    /** More than this is almost certainly a typo in the plot size. */
    public static final int MAX_PLOTS = 4096;

    private final XPlots plugin;
    private int lastFieldId;

    public Builder(XPlots plugin, int lastFieldId) {
        this.plugin = plugin;
        this.lastFieldId = lastFieldId;
    }

    /** Starts outlining a field for the player; pass what it returns to {@link #stopPreview}. */
    public Object preview(org.bukkit.entity.Player player, String world, Layout layout) {
        return new Preview(plugin, player, world, layout, plugin.config().signs().placement());
    }

    public void stopPreview(Object preview) {
        if (preview instanceof Preview running) {
            running.stop();
        }
    }

    /** Why a field can't go here, or null if it can. */
    public String problem(String name, String world, Layout layout) {
        if (!NAME.matcher(name).matches()) {
            return "Field names are up to 24 lowercase letters, digits and _.";
        }
        if (plugin.plots().field(name) != null) {
            return "There's already a field called " + name + ".";
        }
        if (layout == null) {
            return "Not even one plot of that size fits in the selection.";
        }
        if (layout.count() > MAX_PLOTS) {
            return layout.count() + " plots is more than the " + MAX_PLOTS + " a field can have. Check the plot size.";
        }
        return overlap(world, layout.area());
    }

    /** Why these columns are taken, or null if they're free. */
    private String overlap(String world, Box box) {
        for (Field field : plugin.plots().fields()) {
            if (!field.world().equals(world)) {
                continue;
            }
            if (field.layout().area().overlapsColumns(box)) {
                return "That overlaps the field " + field.name() + ".";
            }
            for (Plot plot : field.plots()) {
                if (plot.box().overlapsColumns(box)) {
                    return "That overlaps the plot " + plot.id() + ".";
                }
            }
        }
        return null;
    }

    /**
     * Makes the field: saves it, saves each plot's blocks as what it's put back to, then
     * places the signs. {@code done} runs once the signs are up.
     */
    public Field createField(String name, World world, Layout layout, Runnable done) {
        Field field = new Field(++lastFieldId, name, world.getName(), layout);
        for (Layout.Spot spot : layout.spots(field.signPlacement(plugin.config().signs().placement()))) {
            field.add(new Plot(field, spot.number(), spot.box(), spot.signX(), spot.signY(), spot.signZ(), spot.facing()));
        }
        plugin.plots().index(field);
        plugin.store().saveField(field);
        plugin.regions().define(field.regionId(), world, layout.area());
        plugin.protectionChanged();
        List<Plot> plots = field.plots();
        plugin.resets().capture(plots, () -> {
            for (Plot plot : plots) {
                plugin.signs().place(plot);
            }
            done.run();
        });
        return field;
    }

    /** Deletes the field: rents end without refunds, signs come down, plots stay as they are. */
    public void deleteField(Field field) {
        World world = Bukkit.getWorld(field.world());
        for (Plot plot : field.plots()) {
            plugin.signs().remove(plot);
            if (world != null && !field.layout().area().overlapsColumns(plot.box())) {
                plugin.regions().remove(field.regionId() + "-" + plot.number(), world);
            }
        }
        if (world != null) {
            plugin.regions().remove(field.regionId(), world);
        }
        plugin.plots().remove(field);
        plugin.store().deleteField(field);
        plugin.expiry().reschedule();
        plugin.protectionChanged();
    }

    /** Why a plot by itself can't go here, or null if it can. */
    public String plotProblem(Field field, String world, Box box) {
        if (!field.world().equals(world)) {
            return "That's in another world from " + field.name() + ".";
        }
        if (box.width() > 256 || box.depth() > 256) {
            return "A plot can be at most 256 blocks across.";
        }
        for (Field other : plugin.plots().fields()) {
            if (!other.world().equals(world)) {
                continue;
            }
            // Inside its own field's paths is fine; on top of a plot, or in another field, isn't.
            if (other != field && other.layout().area().overlapsColumns(box)) {
                return "That overlaps the field " + other.name() + ".";
            }
            for (Plot plot : other.plots()) {
                if (plot.box().overlapsColumns(box)) {
                    return "That overlaps the plot " + plot.id() + ".";
                }
            }
        }
        return null;
    }

    /** Makes one plot from a box, with its sign outside its south edge, placed as the field's signs are. */
    public Plot createPlot(Field field, World world, Box box) {
        SignPlacement.Spot sign = field.signPlacement(plugin.config().signs().placement()).place(box, BlockFace.SOUTH, true);
        Plot plot = new Plot(field, field.nextNumber(), box, sign.x(), Math.min(box.minY() + 1, box.maxY()), sign.z(), sign.facing());
        plugin.plots().add(plot);
        plugin.store().savePlot(plot);
        if (!field.layout().area().overlapsColumns(box)) {
            plugin.regions().define(field.regionId() + "-" + plot.number(), world, box);
        }
        plugin.resets().capture(List.of(plot), () -> plugin.signs().place(plot));
        return plot;
    }

    /**
     * Puts every sign in the field where its sign settings say, undoing any moved by hand.
     * Grid plots face their walkway; plots made by themselves face south.
     *
     * @return how many signs moved
     */
    public int placeSigns(Field field) {
        SignPlacement placement = field.signPlacement(plugin.config().signs().placement());
        java.util.Map<Integer, Layout.Spot> grid = new java.util.HashMap<>();
        for (Layout.Spot spot : field.layout().spots(placement)) {
            grid.put(spot.number(), spot);
        }
        int moved = 0;
        for (Plot plot : field.plots()) {
            Layout.Spot spot = grid.get(plot.number());
            int x;
            int z;
            BlockFace facing;
            if (spot != null && spot.box().equals(plot.box())) {
                x = spot.signX();
                z = spot.signZ();
                facing = spot.facing();
            } else {
                SignPlacement.Spot sign = placement.place(plot.box(), BlockFace.SOUTH, true);
                x = sign.x();
                z = sign.z();
                facing = sign.facing();
            }
            int y = Math.min(plot.box().minY() + 1, plot.box().maxY());
            if (x == plot.signX() && y == plot.signY() && z == plot.signZ() && facing == plot.signFacing()) {
                continue;
            }
            plugin.signs().remove(plot);
            plugin.plots().moveSign(plot, x, y, z, facing);
            plugin.store().savePlot(plot);
            plugin.signs().place(plot);
            moved++;
        }
        return moved;
    }

    /** Deletes one plot: its rent ends, its sign comes down, its blocks stay. */
    public void deletePlot(Plot plot) {
        World world = Bukkit.getWorld(plot.field().world());
        plugin.signs().remove(plot);
        if (world != null && !plot.field().layout().area().overlapsColumns(plot.box())) {
            plugin.regions().remove(plot.field().regionId() + "-" + plot.number(), world);
        }
        plugin.plots().remove(plot);
        plugin.store().deletePlot(plot);
        plugin.expiry().reschedule();
    }
}
