package co.xenastudios.zchat.chat;

import co.xenastudios.zchat.config.Settings;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The chat filter, compiled once per config load. Words are matched as whole words,
 * and with {@code smart} on, against the {@linkplain Normal folded} message too, so
 * spacing, number swaps, accents and stretched letters don't get past it. Patterns are
 * regular expressions matched anywhere in the message as typed.
 */
public final class ChatFilter {

    /** What the filter made of a message. */
    public record Result(boolean matched, String text) {}

    private static final String WORD_CHAR = "[\\p{L}\\p{N}]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private final boolean censor;
    private final String censorChar;
    /** Matches a whole folded token that's one of the words, or null if there are none. */
    private final Pattern smartWords;
    /** Matches the words in the message as typed, or null if there are none. */
    private final Pattern plainWords;
    private final List<Pattern> patterns;

    private ChatFilter(boolean censor, String censorChar, Pattern smartWords, Pattern plainWords, List<Pattern> patterns) {
        this.censor = censor;
        this.censorChar = censorChar;
        this.smartWords = smartWords;
        this.plainWords = plainWords;
        this.patterns = patterns;
    }

    /** A filter that never matches. */
    public static final ChatFilter NONE = new ChatFilter(true, "*", null, null, List.of());

    /**
     * Compiles the filter's settings. Patterns that aren't valid regular expressions
     * are left out, with a warning added to {@code warnings}.
     */
    public static ChatFilter compile(Settings.Filter settings, List<String> warnings) {
        if (!settings.enabled()) {
            return NONE;
        }
        List<String> smart = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        for (String word : settings.words()) {
            String trimmed = word.trim();
            boolean before = trimmed.startsWith("*");
            boolean after = trimmed.endsWith("*") && trimmed.length() > 1;
            String core = trimmed.substring(before ? 1 : 0, trimmed.length() - (after ? 1 : 0));
            if (core.isBlank()) {
                continue;
            }
            plain.add((before ? WORD_CHAR + "*" : "") + Pattern.quote(core) + (after ? WORD_CHAR + "*" : ""));
            String folded = Normal.fold(core);
            if (settings.smart() && folded.length() < 2) {
                // "c++" would fold to "c" and catch every lone c.
                warnings.add("filter.words: \"" + word + "\" is mostly symbols, so it's matched only as typed, not smartly.");
            } else if (settings.smart()) {
                smart.add((before ? WORD_CHAR + "*" : "") + stretchable(folded) + (after ? WORD_CHAR + "*" : ""));
            }
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String pattern : settings.patterns()) {
            try {
                patterns.add(Pattern.compile(pattern, FLAGS));
            } catch (PatternSyntaxException e) {
                warnings.add("filter.patterns: \"" + pattern + "\" isn't a valid regular expression (" + e.getDescription() + "), so it's skipped.");
            }
        }
        return new ChatFilter(
            settings.mode() == Settings.FilterMode.CENSOR,
            settings.censorChar(),
            smart.isEmpty() ? null : Pattern.compile("(?:" + String.join("|", smart) + ")", FLAGS),
            plain.isEmpty() ? null : Pattern.compile("(?<!" + WORD_CHAR + ")(?:" + String.join("|", plain) + ")(?!" + WORD_CHAR + ")", FLAGS),
            List.copyOf(patterns));
    }

    // "baad" -> "b+a{2,}d+": each run of a letter matches that many or more.
    private static String stretchable(String folded) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < folded.length(); ) {
            char c = folded.charAt(i);
            int run = 1;
            while (i + run < folded.length() && folded.charAt(i + run) == c) {
                run++;
            }
            regex.append(Pattern.quote(String.valueOf(c))).append(run == 1 ? "+" : "{" + run + ",}");
            i += run;
        }
        return regex.toString();
    }

    public boolean isEmpty() {
        return smartWords == null && plainWords == null && patterns.isEmpty();
    }

    /** Checks a message, censoring what matched when the filter censors. */
    public Result apply(String message) {
        if (isEmpty()) {
            return new Result(false, message);
        }
        BitSet hit = new BitSet(message.length());
        if (plainWords != null) {
            mark(plainWords.matcher(message), hit);
        }
        for (Pattern pattern : patterns) {
            mark(pattern.matcher(message), hit);
        }
        if (smartWords != null) {
            Normal normal = Normal.of(message);
            Matcher matcher = smartWords.matcher("");
            for (Normal.Token token : normal.tokens()) {
                matcher.reset(normal.letters(token));
                if (!token.joined()) {
                    if (matcher.matches()) {
                        hit.set(normal.origin[token.start()], normal.origin[token.end() - 1] + 1);
                    }
                    continue;
                }
                // In "u r a b a d w o r d" every letter is a word, so any part can be one.
                // A run of digits alone ("4 5 5") is numbers, not letters in disguise.
                int[] positions = normal.letterPositions(token);
                if (onlyDigits(message, normal, positions)) {
                    continue;
                }
                while (matcher.find()) {
                    if (matcher.end() > matcher.start()) {
                        hit.set(normal.origin[positions[matcher.start()]], normal.origin[positions[matcher.end() - 1]] + 1);
                    }
                }
            }
        }
        if (hit.isEmpty()) {
            return new Result(false, message);
        }
        return new Result(true, censor ? censored(message, hit) : message);
    }

    private static boolean onlyDigits(String message, Normal normal, int[] positions) {
        for (int position : positions) {
            if (!Character.isDigit(message.codePointAt(normal.origin[position]))) {
                return false;
            }
        }
        return true;
    }

    private static void mark(Matcher matcher, BitSet hit) {
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                hit.set(matcher.start(), matcher.end());
            }
        }
    }

    // Every matched character but spaces becomes the censor character, one per code
    // point; accents and invisible characters straight after a censored one go with it.
    private String censored(String message, BitSet hit) {
        StringBuilder out = new StringBuilder(message.length());
        boolean censoring = false;
        for (int i = 0; i < message.length(); ) {
            int codePoint = message.codePointAt(i);
            int type = Character.getType(codePoint);
            boolean invisible = type == Character.NON_SPACING_MARK || type == Character.FORMAT;
            if (censoring && invisible) {
                // Dropped with the letter it belonged to.
            } else if (hit.get(i) && !Character.isWhitespace(codePoint)) {
                out.append(censorChar);
                censoring = true;
            } else {
                out.appendCodePoint(codePoint);
                censoring = false;
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
