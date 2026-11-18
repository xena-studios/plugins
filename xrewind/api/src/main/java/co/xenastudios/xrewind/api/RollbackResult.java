package co.xenastudios.xrewind.api;

import java.time.Duration;
import org.jetbrains.annotations.NotNull;

/**
 * What applying a {@link Plan} did.
 *
 * @param applied how many of the plan's changes were made
 * @param skipped how many couldn't be: states this server can't read, and worlds that
 *     aren't loaded
 * @param took how long it took, from start to finish
 * @param undo the plan that puts back what this one changed; apply it with
 *     {@link XRewindApi#apply(Plan)} to undo. In a {@code RollbackFinishedEvent} for a
 *     rollback a command ran, it's the sender's {@code /xr undo} and can't be applied
 * @since 1.1.0
 */
public record RollbackResult(int applied, int skipped, @NotNull Duration took, @NotNull Plan undo) {}
