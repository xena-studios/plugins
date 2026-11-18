package co.xenastudios.xrewind.api;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One logged change, as a lookup returns it.
 *
 * @param time when it happened, to the second
 * @param actor who did it: a player's name, or a source such as {@code #tnt}
 * @param world the world's name
 * @param x the block's x
 * @param y the block's y
 * @param z the block's z
 * @param action what kind of change
 * @param target what it was about. For a block change, the block's state before, such as
 *     {@code minecraft:oak_stairs[facing=east]}; for a container change, the item's type,
 *     such as {@code minecraft:diamond}; for a kill, the entity's type; for a sign, chat
 *     message or command, the text; for a join or quit, empty
 * @param after for a block change, the block's state after; otherwise null
 * @param amount for a container change, how many items; otherwise 0
 * @param rolledBack whether a rollback has undone it
 * @since 1.1.0
 */
public record Entry(@NotNull Instant time, @NotNull String actor, @NotNull String world, int x, int y, int z,
                    @NotNull Action action, @NotNull String target, @Nullable String after, int amount,
                    boolean rolledBack) {}
