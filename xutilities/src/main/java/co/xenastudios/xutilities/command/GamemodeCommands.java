package co.xenastudios.xutilities.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.task.Tasks;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xutilities.config.Settings.PlayerCommand;
import co.xenastudios.xutilities.config.XConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** /survival, /creative, /adventure, /spectator and /gamemode. */
final class GamemodeCommands {

    private GamemodeCommands() {}

    static void register(Setup setup, Tasks tasks, XConfig.Commands commands) {
        Map<GameMode, PlayerCommand> modes = new LinkedHashMap<>();
        modes.put(GameMode.SURVIVAL, commands.survival());
        modes.put(GameMode.CREATIVE, commands.creative());
        modes.put(GameMode.ADVENTURE, commands.adventure());
        modes.put(GameMode.SPECTATOR, commands.spectator());
        modes.values().removeIf(settings -> !settings.enabled());

        modes.forEach((mode, settings) -> {
            String name = name(mode);
            setup.permission(settings.permissionOthers(), PermissionDefault.OP);
            setup.command(Specs.named(name, settings)
                .description("Switch to " + name + " mode.")
                .usage("/" + name + " [player]")
                .completes((sender, args) -> args.length == 1
                    ? Completions.players(sender, args[0], settings.permissionOthers()) : List.of())
                .executes(invocation -> set(tasks, invocation, invocation.target(0, settings.permissionOthers()), mode, settings)));
        });

        var gamemode = commands.gamemode();
        if (!gamemode.enabled() || modes.isEmpty()) {
            return;
        }
        setup.permission(gamemode.permissionOthers(), PermissionDefault.OP);
        setup.command(Specs.named("gamemode", gamemode)
            .description("Switch gamemode.")
            .usage("/gamemode <" + String.join("|", modes.keySet().stream().map(GamemodeCommands::name).toList()) + "> [player]")
            .completes((sender, args) -> switch (args.length) {
                case 1 -> Completions.matching(args[0], modes.entrySet().stream()
                    .filter(entry -> entry.getValue().permission().isBlank() || sender.hasPermission(entry.getValue().permission()))
                    .map(entry -> name(entry.getKey()))
                    .toList());
                case 2 -> Completions.players(sender, args[1], gamemode.permissionOthers());
                default -> List.of();
            })
            .executes(invocation -> {
                GameMode mode = parse(invocation.arg(0), modes);
                if (mode == null) {
                    throw invocation.usage();
                }
                PlayerCommand settings = modes.get(mode);
                invocation.require(settings.permission());
                Player target = invocation.target(1, gamemode.permissionOthers());
                set(tasks, invocation, target, mode, settings);
            }));
    }

    private static void set(Tasks tasks, Invocation invocation, Player target, GameMode mode, PlayerCommand settings) {
        Specs.change(tasks, invocation, target, player -> player.setGameMode(mode),
            settings.messageSelf(), settings.messageOthers(), Placeholders.text("mode", name(mode)));
    }

    /** A mode by name, number, first letters ("sp"), or one of its command's aliases ("gmc"). */
    static GameMode parse(String typed, Map<GameMode, PlayerCommand> modes) {
        if (typed == null) {
            return null;
        }
        String lower = typed.toLowerCase(Locale.ROOT);
        for (Map.Entry<GameMode, PlayerCommand> entry : modes.entrySet()) {
            GameMode mode = entry.getKey();
            List<String> names = new ArrayList<>(entry.getValue().aliases());
            names.add(name(mode));
            names.add(Integer.toString(mode.getValue()));
            names.add(mode == GameMode.SPECTATOR ? "sp" : name(mode).substring(0, 1));
            if (names.contains(lower)) {
                return mode;
            }
        }
        return null;
    }

    static String name(GameMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
