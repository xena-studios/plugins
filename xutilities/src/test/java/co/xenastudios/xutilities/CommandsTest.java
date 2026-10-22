package co.xenastudios.xutilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CommandsTest {

    ServerMock server;
    XUtilities plugin;
    PlayerMock admin;
    PlayerMock player;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(XUtilities.class);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        player = server.addPlayer("Steve");
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    String next(PlayerMock who) {
        var message = who.nextComponentMessage();
        return message == null ? null : PlainTextComponentSerializer.plainText().serialize(message);
    }

    @Test
    void flyTogglesAndTellsBothPlayers() {
        admin.performCommand("fly");
        assertTrue(admin.getAllowFlight());
        assertEquals("» Flight enabled.", next(admin));

        admin.performCommand("fly Steve");
        assertTrue(player.getAllowFlight());
        assertEquals("» Flight enabled.", next(player));
        assertEquals("» Flight enabled for Steve.", next(admin));

        admin.performCommand("flight");
        assertFalse(admin.getAllowFlight());
    }

    @Test
    void commandsDefaultToOpsAndOthersNeedTheirOwnPermission() {
        player.performCommand("fly");
        assertFalse(player.getAllowFlight());
        assertEquals("» You don't have permission to do that.", next(player));

        player.addAttachment(plugin, "xutilities.command.heal", true);
        player.performCommand("heal Admin");
        assertEquals("» You don't have permission to do that.", next(player));
        // Naming yourself isn't acting on someone else.
        player.performCommand("heal steve");
        assertEquals("» You've been healed.", next(player));
    }

    @Test
    void pingAndLinkCommandsAreEveryones() {
        assertEquals(PermissionDefault.TRUE, Bukkit.getPluginManager().getPermission("xutilities.command.ping").getDefault());
        assertEquals(PermissionDefault.TRUE, Bukkit.getPluginManager().getPermission("xutilities.command.discord").getDefault());
        assertEquals(PermissionDefault.OP, Bukkit.getPluginManager().getPermission("xutilities.command.fly.others").getDefault());
        player.performCommand("discord");
        assertEquals("» Join our Discord: discord.gg/example", next(player));
    }

    @Test
    void unknownPlayersAndBadArguments() {
        admin.performCommand("heal Nobody");
        assertEquals("» No player named Nobody is online.", next(admin));
        admin.performCommand("speed 11");
        assertEquals("» Speed must be from 1 to 10.", next(admin));
        admin.performCommand("setrotation up");
        assertEquals("» Usage: /setrotation <yaw> <pitch> [player]", next(admin));
        server.getConsoleSender().getServer().dispatchCommand(server.getConsoleSender(), "anvil");
    }

    @Test
    void adminCommandAnswersToXu() {
        admin.performCommand("xu info");
        assertTrue(next(admin).startsWith("» xUtilities 2.0.0 on "));
    }

    @Test
    void speedSetsWalkSpeedOnTheOneToTenScale() {
        admin.performCommand("speed 5");
        assertEquals(0.5f, admin.getWalkSpeed(), 0.0001);
        assertEquals("» Your walking speed is now 5.", next(admin));
    }

    @Test
    void gamemodeTakesNamesNumbersAndAliases() {
        admin.performCommand("gamemode c");
        assertEquals(GameMode.CREATIVE, admin.getGameMode());
        admin.performCommand("gm 2 Steve");
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
        admin.performCommand("gm gmsp");
        assertEquals(GameMode.SPECTATOR, admin.getGameMode());
        admin.performCommand("gms");
        assertEquals(GameMode.SURVIVAL, admin.getGameMode());
    }

    @Test
    void healRestoresEverythingTheConfigSays() {
        admin.setHealth(3);
        admin.setFoodLevel(2);
        admin.setFireTicks(100);
        // (MockBukkit can't tell harmful effects apart, so clearing them isn't tested here.)
        admin.performCommand("heal");
        assertEquals(20, admin.getHealth());
        assertEquals(20, admin.getFoodLevel());
        assertEquals(0, admin.getFireTicks());
        assertEquals("» You've been healed.", next(admin));
    }

    @Test
    void repairAndClear() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.editMeta(Damageable.class, meta -> meta.setDamage(500));
        admin.getInventory().setItemInMainHand(sword);
        admin.performCommand("repair");
        assertEquals("» Your gear has been repaired.", next(admin));
        assertEquals(0, ((Damageable) admin.getInventory().getItemInMainHand().getItemMeta()).getDamage());
        admin.performCommand("fix");
        assertEquals("» Nothing needs repairing.", next(admin));

        admin.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        admin.getInventory().addItem(new ItemStack(Material.DIRT, 5));
        admin.performCommand("ci");
        assertTrue(admin.getInventory().isEmpty());
        assertNull(admin.getInventory().getHelmet());
    }

    @Test
    void reloadAppliesCommandChangesWithoutARestart() throws Exception {
        assertNotNull(server.getCommandMap().getCommand("fly"));
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String text = Files.readString(config);
        text = text.replaceFirst("(?s)(  fly:\\n    enabled: )true", "$1false")
            .replace("aliases: [\"setspeed\"]", "aliases: [\"zoom\"]")
            .replace("  website:\n", "  vote:\n    message: \"<green>Vote!\"\n  website:\n");
        Files.writeString(config, text);

        plugin.reload();
        assertNull(server.getCommandMap().getCommand("fly"), "turned off");
        assertNull(server.getCommandMap().getCommand("flight"), "its aliases too");
        assertNull(server.getCommandMap().getCommand("setspeed"), "old alias gone");
        assertNotNull(server.getCommandMap().getCommand("zoom"), "new alias added");
        player.performCommand("vote");
        assertEquals("Vote!", next(player));
    }

    @Test
    void aLinkCommandThatClashesIsSkipped() throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace("  website:\n", "  heal:\n    message: \"nope\"\n  website:\n"));
        plugin.reload();
        admin.setHealth(3);
        admin.performCommand("heal");
        assertEquals(20, admin.getHealth(), "/heal is still xUtilities' heal");
    }

    @Test
    void backReturnsToTheLastTeleport() {
        var start = admin.getLocation().clone();
        admin.performCommand("back");
        assertEquals("» You have nowhere to go back to.", next(admin));
        admin.teleport(start.clone().add(100, 0, 0));
        assertEquals(start.getX() + 100, admin.getLocation().getX(), 0.001);
        // MockBukkit can't teleportAsync, so this checks /back found the location and
        // tried; the teleport itself is checked on a real server.
        admin.performCommand("back");
        assertNotEquals("» You have nowhere to go back to.", next(admin));

        server.getPluginManager().callEvent(new PlayerQuitEvent(admin, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        admin.performCommand("back");
        assertEquals("» You have nowhere to go back to.", next(admin), "forgotten on quit");
    }
}
