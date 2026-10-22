package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.xutilities.config.Settings;
import co.xenastudios.xutilities.config.XConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;

/** The workstation menus and /trash. */
final class MenuCommands {

    /** /trash's size: four rows. */
    private static final int TRASH_SLOTS = 36;

    private MenuCommands() {}

    static void register(Setup setup, Tasks tasks, XConfig.Commands commands) {
        Map<String, Station> stations = new LinkedHashMap<>();
        stations.put("anvil", new Station(commands.anvil(), MenuType.ANVIL, "an anvil"));
        stations.put("craft", new Station(commands.craft(), MenuType.CRAFTING, "a crafting table"));
        stations.put("grinder", new Station(commands.grinder(), MenuType.GRINDSTONE, "a grindstone"));
        stations.put("loom", new Station(commands.loom(), MenuType.LOOM, "a loom"));
        stations.put("smith", new Station(commands.smith(), MenuType.SMITHING, "a smithing table"));
        stations.put("stonecutter", new Station(commands.stonecutter(), MenuType.STONECUTTER, "a stonecutter"));
        stations.put("cartography", new Station(commands.cartography(), MenuType.CARTOGRAPHY_TABLE, "a cartography table"));

        stations.forEach((name, station) -> {
            if (!station.settings().enabled()) {
                return;
            }
            setup.command(Specs.named(name, station.settings())
                .description("Open " + station.what() + ".")
                .executes(invocation -> {
                    Player player = invocation.player();
                    tasks.entity(player, () -> {
                        // checkReachable(false): the menu stays open with no block nearby.
                        station.menu().builder().checkReachable(false).build(player).open();
                        station.settings().message().send(player);
                    });
                }));
        });

        Settings.Trash trash = commands.trash();
        if (trash.enabled()) {
            setup.command(Specs.named("trash", trash)
                .description("Open a menu that deletes what's left in it.")
                .executes(invocation -> {
                    Player player = invocation.player();
                    tasks.entity(player, () -> {
                        // Nothing holds this inventory, so whatever's in it is gone once it closes.
                        player.openInventory(Bukkit.createInventory(null, TRASH_SLOTS, trash.title().render()));
                        trash.message().send(player);
                    });
                }));
        }
    }

    private record Station(Settings.Workstation settings, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu, String what) {}
}
