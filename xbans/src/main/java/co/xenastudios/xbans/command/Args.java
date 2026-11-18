package co.xenastudios.xbans.command;

import co.xenastudios.xbans.model.Length;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A punishment command's arguments, read the LiteBans way:
 * {@code /ban <player> [flags] [duration] [server:<name>] [template] [reason]}. Flags and
 * {@code server:} may stand anywhere after the player; everything after {@code --} is
 * reason, even a word that looks like a flag.
 *
 * <ul>
 *   <li>{@code -s} silent: only staff are told. {@code -S} extra silent: only the console.
 *   <li>{@code -p} public, when punishments are silent by default.
 *   <li>{@code -g} global: {@code server:*}. {@code -I} by IP too, as {@code /ipban}.
 *   <li>{@code server:<name>}: one server, {@code server:*}, {@code global} or {@code all}
 *       for every server, {@code local} or {@code this} for this one.
 * </ul>
 *
 * @param scope null when none was given
 * @param length null when none was given
 * @param template null when none was given
 * @param reason null when none was given
 */
public record Args(
    String target,
    boolean silent,
    boolean extraSilent,
    boolean publicFlag,
    boolean ip,
    String scope,
    Length length,
    String template,
    String reason,
    String badLength) {

    /** The scope words that mean every server. */
    public static boolean isGlobal(String scope) {
        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "*", "global", "all" -> true;
            default -> false;
        };
    }

    public static boolean isLocal(String scope) {
        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "local", "this" -> true;
            default -> false;
        };
    }

    /**
     * Reads the arguments after the command's name.
     *
     * @param wantsLength whether a length may come first (bans, mutes, warnings; not kicks or unbans)
     * @param isTemplate whether a word names a template this command can use
     * @return null when there's no player
     */
    public static Args parse(String[] args, boolean wantsLength, Predicate<String> isTemplate) {
        if (args.length == 0 || args[0].isBlank()) {
            return null;
        }
        boolean silent = false;
        boolean extraSilent = false;
        boolean publicFlag = false;
        boolean ip = false;
        String scope = null;
        List<String> words = new ArrayList<>();
        boolean literal = false;
        for (int i = 1; i < args.length; i++) {
            String word = args[i];
            if (word.isEmpty()) {
                continue;
            }
            if (literal) {
                words.add(word);
                continue;
            }
            String lower = word.toLowerCase(Locale.ROOT);
            switch (word) {
                case "--" -> literal = true;
                case "-s" -> silent = true;
                case "-S" -> extraSilent = true;
                case "-p" -> publicFlag = true;
                case "-g" -> scope = "*";
                case "-I" -> ip = true;
                default -> {
                    if (lower.startsWith("server:") && word.length() > "server:".length()) {
                        scope = word.substring("server:".length());
                    } else {
                        words.add(word);
                    }
                }
            }
        }
        Length length = null;
        String badLength = null;
        int next = 0;
        if (wantsLength && !words.isEmpty()) {
            String first = words.get(0);
            try {
                // "5 days", typed with a space.
                if (words.size() > 1 && first.chars().allMatch(Character::isDigit) && Length.isUnit(words.get(1))) {
                    length = Length.parse(first + words.get(1));
                    next = length == null ? 0 : 2;
                } else {
                    length = Length.parse(first);
                    next = length == null ? 0 : 1;
                }
            } catch (IllegalArgumentException e) {
                badLength = first;
                next = 1;
            }
            if (length == null && badLength == null && looksLikeLength(first)) {
                // "10x" or "3dd": meant as a length, so say so rather than make it the reason.
                badLength = first;
                next = 1;
            }
        }
        String template = null;
        if (next < words.size() && isTemplate.test(words.get(next).toLowerCase(Locale.ROOT))) {
            template = words.get(next).toLowerCase(Locale.ROOT);
            next++;
        }
        String reason = next < words.size() ? String.join(" ", words.subList(next, words.size())) : null;
        return new Args(args[0], silent, extraSilent, publicFlag, ip, scope, length, template, reason, badLength);
    }

    // A short number and unit letters, such as "3dd" or "1h30": a length typed wrong, not the start of a reason.
    private static boolean looksLikeLength(String word) {
        return word.length() <= 8 && LENGTHISH.matcher(word.toLowerCase(Locale.ROOT)).matches();
    }

    private static final java.util.regex.Pattern LENGTHISH = java.util.regex.Pattern.compile("\\d[\\d.smhdwoy]*[smhdwoy][\\d.smhdwoy]*");
}
