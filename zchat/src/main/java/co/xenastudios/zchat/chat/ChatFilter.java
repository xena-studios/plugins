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

    /**
     * What the filter made of a message.
     *
     * @param hits which characters of the message matched, or null if none did
     */
    public record Result(boolean matched, String text, BitSet hits) {

        /** The parts of {@code message} that matched, as typed: for the API's filter event. */
        public List<String> matches(String message) {
            List<String> parts = new ArrayList<>();
            if (hits == null) {
                return parts;
            }
            for (int start = hits.nextSetBit(0); start >= 0; start = hits.nextSetBit(start)) {
                int end = hits.nextClearBit(start);
                parts.add(message.substring(start, Math.min(end, message.length())));
                start = end;
            }
            return parts;
        }
    }

    private static final String WORD_CHAR = "[\\p{L}\\p{N}]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private final boolean censor;
    private final String censorChar;
    /** Matches a whole folded token that's one of the words, or null if there are none. */
    private final Pattern smartWords;
    /** Like {@link #smartWords}, but only the words without a *: for words split into short pieces. */
    private final Pattern smartWhole;
    /** Matches the words in the message as typed, or null if there are none. */
    private final Pattern plainWords;
    private final List<Pattern> patterns;

    /** A word split into short pieces ("ba dw or d") is looked for across at most this many of them. */
    private static final int MAX_PIECES = 16;
    /** The longest a piece can be and still be joined to the pieces beside it. */
    private static final int SHORT_PIECE = 2;

    private ChatFilter(boolean censor, String censorChar, Pattern smartWords, Pattern smartWhole, Pattern plainWords, List<Pattern> patterns) {
        this.censor = censor;
        this.censorChar = censorChar;
        this.smartWords = smartWords;
        this.smartWhole = smartWhole;
        this.plainWords = plainWords;
        this.patterns = patterns;
    }

    /** A filter that never matches. */
    public static final ChatFilter NONE = new ChatFilter(true, "*", null, null, null, List.of());

    /**
     * Compiles the filter's settings. Patterns that aren't valid regular expressions
     * are left out, with a warning added to {@code warnings}.
     */
    public static ChatFilter compile(Settings.Filter settings, List<String> warnings) {
        if (!settings.enabled()) {
            return NONE;
        }
        List<String> smart = new ArrayList<>();
        List<String> whole = new ArrayList<>();
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
                if (!before && !after) {
                    whole.add(stretchable(folded));
                }
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
            whole.isEmpty() ? null : Pattern.compile("(?:" + String.join("|", whole) + ")", FLAGS),
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
            return new Result(false, message, null);
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
            List<Normal.Token> words = normal.words();
            for (Normal.Token token : Normal.tokens(words)) {
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
            if (smartWhole != null) {
                markSplitWords(message, normal, words, hit);
            }
        }
        if (hit.isEmpty()) {
            return new Result(false, message, null);
        }
        return new Result(true, censor ? censored(message, hit) : message, hit);
    }

    /**
     * A word split into short pieces, "ba dw or d": runs of pieces of one or two letters,
     * at least one of them two, are joined and must make a whole filter word, never just
     * contain one, so ordinary short words ("is it to an") are left alone. Words with a *
     * are left out: "as so" would make "*ass*".
     */
    private void markSplitWords(String message, Normal normal, List<Normal.Token> words, BitSet hit) {
        Matcher matcher = smartWhole.matcher("");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).length() > SHORT_PIECE) {
                continue;
            }
            joined.setLength(0);
            joined.append(normal.text, words.get(i).start(), words.get(i).end());
            boolean twoLetters = words.get(i).length() == SHORT_PIECE;
            for (int j = i + 1; j < words.size() && j - i < MAX_PIECES && words.get(j).length() <= SHORT_PIECE; j++) {
                Normal.Token piece = words.get(j);
                joined.append(normal.text, piece.start(), piece.end());
                twoLetters |= piece.length() == SHORT_PIECE;
                if (!twoLetters || !matcher.reset(joined).matches()) {
                    continue;
                }
                int start = words.get(i).start();
                int[] positions = normal.letterPositions(new Normal.Token(start, piece.end(), true));
                if (!onlyDigits(message, normal, positions)) {
                    hit.set(normal.origin[start], normal.origin[piece.end() - 1] + 1);
                }
            }
        }
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
