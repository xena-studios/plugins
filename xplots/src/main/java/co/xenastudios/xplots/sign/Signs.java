package co.xenastudios.xplots.sign;

import co.xenastudios.core.text.Message;
import co.xenastudios.xplots.config.XPConfig;
import co.xenastudios.xplots.model.Plot;
import co.xenastudios.xplots.model.Plots;
import co.xenastudios.xplots.rent.Info;
import co.xenastudios.xplots.rent.Wallet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

/**
 * Plot signs. A sign is only written when what it shows has changed, and only while its
 * chunk is loaded; a chunk that loads gets its signs brought up to date. Once a minute
 * the signs of rented plots are checked (their time left changes), and any sign that's
 * gone missing is put back. Main thread.
 */
public final class Signs {

    private final Plots plots;
    private final Supplier<XPConfig> config;
    private final Wallet wallet;

    // Compiled sign lines, kept until the config changes.
    private XPConfig compiledFrom;
    private List<Message> free;
    private List<Message> rented;
    private List<Message> expiring;
    // Bumped when the lines change on reload, so every sign is rewritten once.
    private int generation;

    public Signs(Plots plots, Supplier<XPConfig> config, Wallet wallet) {
        this.plots = plots;
        this.config = config;
        this.wallet = wallet;
    }

    /** Brings the plot's sign up to date if its chunk is loaded, putting it back if it's gone. */
    public void update(Plot plot) {
        World world = Bukkit.getWorld(plot.field().world());
        if (world == null || !world.isChunkLoaded(plot.signX() >> 4, plot.signZ() >> 4)) {
            return;
        }
        XPConfig current = config.get();
        compile(current);
        long now = System.currentTimeMillis();
        XPConfig.PlotSettings settings = plot.field().settings(current.plots());
        Info info = Info.of(plot, settings, wallet, now, "");
        List<Message> lines = plot.isFree() ? free
            : plot.lease().remaining(now) < current.signs().expiringBelow().toMillis() ? expiring : rented;
        String key = generation + (lines == free ? "f" : lines == rented ? "r" : "e") + '|' + info.price() + '|' + info.period()
            + '|' + info.owner() + '|' + info.time() + '|' + info.members() + '|' + info.slots();
        Block block = world.getBlockAt(plot.signX(), plot.signY(), plot.signZ());
        boolean missing = !(Tag.STANDING_SIGNS.isTagged(block.getType()) || Tag.WALL_SIGNS.isTagged(block.getType()));
        if (!missing && key.equals(plot.shown())) {
            return;
        }
        if (missing) {
            place(block, plot, current.signs().material());
        }
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }
        TagResolver[] placeholders = info.placeholders();
        SignSide front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            front.line(i, i < lines.size() ? lines.get(i).render(placeholders) : net.kyori.adventure.text.Component.empty());
        }
        // Players can't edit it.
        sign.setWaxed(true);
        sign.update(true, false);
        plot.shown(key);
    }

    /**
     * Places the plot's sign where it belongs, facing the path, and fills it in. In a
     * chunk that isn't loaded, it's placed when the chunk loads.
     */
    public void place(Plot plot) {
        plot.shown(null);
        World world = Bukkit.getWorld(plot.field().world());
        if (world != null && world.isChunkLoaded(plot.signX() >> 4, plot.signZ() >> 4)) {
            place(world.getBlockAt(plot.signX(), plot.signY(), plot.signZ()), plot, config.get().signs().material());
            update(plot);
        }
    }

    private static void place(Block block, Plot plot, Material material) {
        Material type = Tag.STANDING_SIGNS.isTagged(material) ? material : Material.OAK_SIGN;
        BlockData data = type.createBlockData();
        if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(plot.signFacing());
        }
        block.setBlockData(data, false);
    }

    /** Takes the plot's sign away, as when the plot is deleted. */
    public void remove(Plot plot) {
        World world = Bukkit.getWorld(plot.field().world());
        if (world != null) {
            Block block = world.getBlockAt(plot.signX(), plot.signY(), plot.signZ());
            if (Tag.ALL_SIGNS.isTagged(block.getType())) {
                block.setType(Material.AIR, false);
            }
        }
    }

    /** The once-a-minute check: every sign in a loaded chunk, rewritten only where it changed. */
    public void refresh() {
        for (var field : plots.fields()) {
            World world = Bukkit.getWorld(field.world());
            if (world == null) {
                continue;
            }
            for (Plot plot : field.plots()) {
                if (world.isChunkLoaded(plot.signX() >> 4, plot.signZ() >> 4)) {
                    update(plot);
                }
            }
        }
    }

    /** A chunk loaded: its signs may be out of date. */
    public void chunkLoaded(World world, int chunkX, int chunkZ) {
        List<Plot> list = plots.signsInChunk(world, chunkX, chunkZ);
        if (!list.isEmpty()) {
            for (Plot plot : new ArrayList<>(list)) {
                update(plot);
            }
        }
    }

    private void compile(XPConfig current) {
        if (current == compiledFrom) {
            return;
        }
        XPConfig.Signs signs = current.signs();
        if (compiledFrom == null || !signs.equals(compiledFrom.signs()) || !current.theme().equals(compiledFrom.theme())) {
            free = signs.free().stream().map(Message::of).toList();
            rented = signs.rented().stream().map(Message::of).toList();
            expiring = signs.expiring().stream().map(Message::of).toList();
            generation++;
        }
        compiledFrom = current;
    }
}
