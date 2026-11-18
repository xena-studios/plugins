package co.xenastudios.xplots.model;

import co.xenastudios.xplots.config.XPConfig.PlotSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.block.BlockFace;

/**
 * A group of plots that share settings: a grid split from one selection, plus any plots
 * made one at a time. Its whole footprint is protected, at every height; plots can be
 * built in only between the selection's lowest and highest blocks. Main thread only.
 */
public final class Field {

    /** What {@link #locate} returns for a protected spot that's no plot: a path, or above or below one. */
    public static final Plot PATH = new Plot(null, 0, new Box(0, 0, 0, 0, 0, 0), 0, 0, 0, BlockFace.SELF);

    private final int id;
    private final String name;
    private final String world;
    private final Layout layout;
    private final Plot[] grid;
    private final List<Plot> custom = new ArrayList<>();
    private final Map<String, String> overrides = new TreeMap<>();
    private PlotSettings settingsBase;
    private PlotSettings settings;
    private String warp;
    private Box bounds;

    public Field(int id, String name, String world, Layout layout) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.layout = layout;
        this.grid = new Plot[layout.count()];
        this.bounds = layout.area();
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String world() {
        return world;
    }

    public Layout layout() {
        return layout;
    }

    /**
     * The plot at a block, {@link #PATH} for the rest of the field's footprint, or null
     * outside it.
     */
    public Plot locate(int x, int y, int z) {
        if (!bounds.containsColumn(x, z)) {
            return null;
        }
        for (int i = 0, n = custom.size(); i < n; i++) {
            Plot plot = custom.get(i);
            if (plot.box().containsColumn(x, z)) {
                return y >= plot.box().minY() && y <= plot.box().maxY() ? plot : PATH;
            }
        }
        if (!layout.area().containsColumn(x, z)) {
            return null;
        }
        int number = layout.numberAt(x, z);
        Plot plot = number == 0 ? null : grid[number - 1];
        if (plot == null || y < plot.box().minY() || y > plot.box().maxY()) {
            return PATH;
        }
        return plot;
    }

    /** Every column the field covers: its grid's selection and any plots made one at a time. */
    public Box bounds() {
        return bounds;
    }

    /** Adds a plot: a grid plot by its number, or one made by itself. */
    public void add(Plot plot) {
        if (plot.number() <= grid.length) {
            grid[plot.number() - 1] = plot;
        } else {
            custom.add(plot);
            Box b = plot.box();
            bounds = new Box(Math.min(bounds.minX(), b.minX()), Math.min(bounds.minY(), b.minY()), Math.min(bounds.minZ(), b.minZ()),
                Math.max(bounds.maxX(), b.maxX()), Math.max(bounds.maxY(), b.maxY()), Math.max(bounds.maxZ(), b.maxZ()));
        }
    }

    public void remove(Plot plot) {
        if (plot.number() <= grid.length && grid[plot.number() - 1] == plot) {
            grid[plot.number() - 1] = null;
        } else {
            custom.remove(plot);
        }
    }

    /** Every plot, grid first, in number order. */
    public List<Plot> plots() {
        List<Plot> all = new ArrayList<>(grid.length + custom.size());
        for (Plot plot : grid) {
            if (plot != null) {
                all.add(plot);
            }
        }
        all.addAll(custom);
        return all;
    }

    /** The number the next plot made by itself gets. */
    public int nextNumber() {
        int max = grid.length;
        for (Plot plot : custom) {
            max = Math.max(max, plot.number());
        }
        return max + 1;
    }

    /** The settings this field changes from the config's, by key, such as {@code price}. */
    public Map<String, String> overrides() {
        return Collections.unmodifiableMap(overrides);
    }

    public void override(String key, String value) {
        if (value == null) {
            overrides.remove(key);
        } else {
            overrides.put(key, value);
        }
        settings = null;
    }

    /** The field's settings: the config's, with its own changes. */
    public PlotSettings settings(PlotSettings defaults) {
        if (settings == null || settingsBase != defaults) {
            settings = Settings.apply(defaults, overrides);
            settingsBase = defaults;
        }
        return settings;
    }

    /** Where this field's signs go: the config's, with the field's own sign settings on top. */
    public SignPlacement signPlacement(SignPlacement defaults) {
        return SignPlacement.of(defaults, overrides);
    }

    /** Where /plots warp takes players, as {@code x,y,z,yaw,pitch}, or null for none. */
    public String warp() {
        return warp;
    }

    public void warp(String warp) {
        this.warp = warp;
    }

    /** The WorldGuard region xPlots made for this field. */
    public String regionId() {
        return "xplots-" + name;
    }

    @Override
    public String toString() {
        return name;
    }
}
