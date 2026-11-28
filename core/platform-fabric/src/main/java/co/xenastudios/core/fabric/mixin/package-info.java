/**
 * The world hooks' mixins (DECISIONS.md, "The world hooks"): each marks what's happening around
 * one of Minecraft's methods (a player using an item, an explosion, an entity's tick) for
 * {@link co.xenastudios.core.fabric.world.WorldPoints}, or hands it a change. Every handler reads
 * {@code WorldPoints.heard} first, so with nothing listening a mixin costs one field read. Only
 * mixins in this package: Mixin loads it as its own.
 */
package co.xenastudios.core.fabric.mixin;
