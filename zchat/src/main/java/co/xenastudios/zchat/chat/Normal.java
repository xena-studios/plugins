package co.xenastudios.zchat.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A message folded the way someone dodging a filter would hope it isn't: lowercase,
 * accents dropped, common number-for-letter swaps undone, and everything that isn't a
 * letter or digit turned into one space. Keeps where each character came from, so a
 * match can be censored in what the player actually typed.
 */
final class Normal {

    /** What a character folds to when it's dropped, not even leaving a gap. */
    private static final char SKIP = '\0';

    /** Folded characters below this are looked up, not worked out. */
    private static final char[] TABLE = new char[0x250];

    static {
        for (char c = 0; c < TABLE.length; c++) {
            TABLE[c] = foldSlowly(c);
        }
    }

    /** The folded text: letters and digits, with single spaces between words. */
    final String text;
    /** For each character of {@link #text}, its index in the original; -1 for spaces. */
    final int[] origin;

    private Normal(String text, int[] origin) {
        this.text = text;
        this.origin = origin;
    }

    static Normal of(String original) {
        StringBuilder text = new StringBuilder(original.length());
        int[] origin = new int[original.length()];
        int length = 0;
        for (int i = 0; i < original.length(); ) {
            int codePoint = original.codePointAt(i);
            char folded = fold(codePoint);
            if (folded != SKIP) {
                if (folded != ' ') {
                    text.append(folded);
                    origin[length++] = i;
                } else if (length > 0 && text.charAt(length - 1) != ' ') {
                    text.append(' ');
                    origin[length++] = -1;
                }
            }
            i += Character.charCount(codePoint);
        }
        if (length > 0 && text.charAt(length - 1) == ' ') {
            length--;
            text.setLength(length);
        }
        int[] trimmed = new int[length];
        System.arraycopy(origin, 0, trimmed, 0, length);
        return new Normal(text.toString(), trimmed);
    }

    /** A filter word folded the same way, with nothing but letters and digits left. */
    static String fold(String word) {
        StringBuilder folded = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); ) {
            int codePoint = word.codePointAt(i);
            char f = fold(codePoint);
            if (f != ' ' && f != SKIP) {
                folded.append(f);
            }
            i += Character.charCount(codePoint);
        }
        return folded.toString();
    }

    /** The words of the text, with runs of single letters ("b a d") joined into one. */
    List<Token> tokens() {
        List<Token> words = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ' ') {
                if (i > start) {
                    words.add(new Token(start, i, false));
                }
                start = i + 1;
            }
        }
        List<Token> joined = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            int j = i;
            while (j + 1 < words.size() && words.get(j).length() == 1 && words.get(j + 1).length() == 1) {
                j++;
            }
            joined.add(j > i ? new Token(words.get(i).start, words.get(j).end, true) : words.get(i));
            i = j;
        }
        return joined;
    }

    /**
     * One word of {@link #text}, from {@code start} up to {@code end}.
     *
     * @param joined whether it's a run of single letters ("b a d"), any part of which can be a word
     */
    record Token(int start, int end, boolean joined) {
        int length() {
            return end - start;
        }
    }

    /** The token's letters, with the spaces of a joined run taken out. */
    String letters(Token token) {
        return text.substring(token.start, token.end).replace(" ", "");
    }

    /** Where each of {@link #letters}' characters is in {@link #text}. */
    int[] letterPositions(Token token) {
        int[] positions = new int[token.length()];
        int count = 0;
        for (int i = token.start; i < token.end; i++) {
            if (text.charAt(i) != ' ') {
                positions[count++] = i;
            }
        }
        return Arrays.copyOf(positions, count);
    }

    private static char fold(int codePoint) {
        if (codePoint < TABLE.length) {
            return TABLE[codePoint];
        }
        if (Character.isBmpCodePoint(codePoint)) {
            return foldSlowly((char) codePoint);
        }
        // Beyond the first plane: maths letters (𝐚) fold to plain ones; tag characters are invisible.
        if (Character.getType(codePoint) == Character.FORMAT) {
            return SKIP;
        }
        if (Character.isLetterOrDigit(codePoint)) {
            char base = Normalizer.normalize(Character.toString(codePoint), Normalizer.Form.NFKD).charAt(0);
            return Character.isHighSurrogate(base) ? ' ' : foldSlowly(base);
        }
        return ' ';
    }

    private static char foldSlowly(char c) {
        switch (c) {
            case '0': return 'o';
            case '1': return 'i';
            case '3': return 'e';
            case '4': return 'a';
            case '5': return 's';
            case '7': return 't';
            case '@': return 'a';
            case '$': return 's';
            default: break;
        }
        if (Character.isLetterOrDigit(c)) {
            // NFKD also turns full-width and maths letters (ａ, 𝐚) into plain ones.
            char base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKD).charAt(0);
            return base != c ? foldSlowly(base) : Character.toLowerCase(c);
        }
        // An accent typed on its own belongs to the letter before it, and invisible
        // characters (zero-width spaces, soft hyphens) are put in to split a word: skip both.
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.FORMAT ? SKIP : ' ';
    }
}
