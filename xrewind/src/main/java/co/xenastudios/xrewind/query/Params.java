package co.xenastudios.xrewind.query;

import co.xenastudios.core.text.Durations;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;

/**
 * CoreProtect's parameters, so habits and guides carry over: {@code u:} players,
 * {@code t:} time, {@code r:} radius, {@code a:} action, {@code i:} (or {@code b:})
 * blocks, {@code e:} exclusions, {@code w:} world, and {@code #preview}, {@code #count},
 * {@code #silent}.
 */
public final class Params {

    private Params() {}

    /**
     * Parses the parameters.
     *
     * @throws IllegalArgumentException with a message for the player
     */
    public static Query parse(List<String> args) {
        List<String> users = new ArrayList<>();
        List<String> excludedUsers = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        List<String> excludedBlocks = new ArrayList<>();
        Set<Query.Action> actions = EnumSet.noneOf(Query.Action.class);
        long seconds = 0;
        int radius = Query.UNSET;
        String world = null;
        boolean preview = false;
        boolean count = false;
        boolean silent = false;
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("#")) {
                switch (lower) {
                    case "#preview" -> preview = true;
                    case "#count", "#sum" -> count = true;
                    case "#silent" -> silent = true;
                    default -> throw new IllegalArgumentException(arg + " isn't a flag. Use #preview, #count or #silent.");
                }
                continue;
            }
            int colon = lower.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException(arg + " isn't a parameter. Use u:, t:, r:, a:, i:, e: or w:, such as t:1h.");
            }
            String key = lower.substring(0, colon);
            String value = arg.substring(colon + 1);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(arg + " needs something after the colon.");
            }
            switch (key) {
                case "u", "user", "users" -> users.addAll(list(value));
                case "t", "time" -> seconds = Durations.parse(value).toSeconds();
                case "r", "radius" -> radius = radius(value);
                case "w", "world" -> world = value;
                case "a", "action" -> {
                    for (String action : list(value)) {
                        actions.addAll(action(action));
                    }
                }
                case "i", "b", "include", "block", "blocks" -> {
                    for (String block : list(value)) {
                        blocks.add(block(block));
                    }
                }
                case "e", "exclude" -> {
                    for (String item : list(value)) {
                        // A block name excludes the block; anything else, the player or source.
                        if (Material.matchMaterial(item) != null && !item.startsWith("#")) {
                            excludedBlocks.add(block(item));
                        } else {
                            excludedUsers.add(item);
                        }
                    }
                }
                default -> throw new IllegalArgumentException(key + ": isn't a parameter. Use u:, t:, r:, a:, i:, e: or w:.");
            }
        }
        return new Query(List.copyOf(users), List.copyOf(excludedUsers), seconds, radius, world, Set.copyOf(actions),
            List.copyOf(blocks), List.copyOf(excludedBlocks), preview, count, silent);
    }

    private static List<String> list(String value) {
        List<String> items = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private static int radius(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("#world")) {
            return Query.WORLD;
        }
        if (lower.equals("#global")) {
            return Query.GLOBAL;
        }
        try {
            int radius = Integer.parseInt(lower);
            if (radius >= 0 && radius <= 1_000_000) {
                return radius;
            }
        } catch (NumberFormatException ignored) {
            // Falls through.
        }
        throw new IllegalArgumentException("r:" + value + " isn't a radius. Use a number of blocks, #world or #global.");
    }

    private static Set<Query.Action> action(String name) {
        return switch (name) {
            case "block" -> EnumSet.of(Query.Action.BREAK, Query.Action.PLACE);
            case "+block", "place" -> EnumSet.of(Query.Action.PLACE);
            case "-block", "break" -> EnumSet.of(Query.Action.BREAK);
            case "click", "interact" -> EnumSet.of(Query.Action.CLICK);
            case "container" -> EnumSet.of(Query.Action.ADD, Query.Action.REMOVE);
            case "+container", "add" -> EnumSet.of(Query.Action.ADD);
            case "-container", "remove", "take" -> EnumSet.of(Query.Action.REMOVE);
            case "kill" -> EnumSet.of(Query.Action.KILL);
            case "sign" -> EnumSet.of(Query.Action.SIGN);
            case "chat" -> EnumSet.of(Query.Action.CHAT);
            case "command" -> EnumSet.of(Query.Action.COMMAND);
            case "session" -> EnumSet.of(Query.Action.JOIN, Query.Action.QUIT);
            case "+session", "login", "join" -> EnumSet.of(Query.Action.JOIN);
            case "-session", "logout", "quit" -> EnumSet.of(Query.Action.QUIT);
            default -> throw new IllegalArgumentException("a:" + name
                + " isn't an action. Use block, +block, -block, container, +container, -container, kill, click, sign, chat, command or session.");
        };
    }

    private static String block(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException(name + " isn't a block.");
        }
        return material.getKey().getKey();
    }
}
