package co.xenastudios.xstats.api;

import org.jetbrains.annotations.NotNull;

/**
 * One line of a {@link ProfileSectionProvider profile section}: a label and its value, as
 * plain text. The website and chat show both as written, never as HTML or MiniMessage.
 *
 * @param label what the value is, such as {@code Votes this month}
 * @param value the value, such as {@code 12}
 * @since 1.0.0
 */
public record ProfileLine(@NotNull String label, @NotNull String value) {}
