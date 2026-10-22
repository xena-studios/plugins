package co.xenastudios.xlimbo.config;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.Key;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.text.Message;
import java.util.List;
import org.bukkit.Material;

/** The record for each part of xLimbo's config. */
public final class Settings {

    private Settings() {}

    public record World(
        @Comment({
            "The limbo world, created on startup. To use the server's main world instead, set",
            "this to its name (level-name in server.properties) and give it xLimbo's generator",
            "in bukkit.yml (worlds: <name>: generator: xLimbo)."})
        String name,
        @Comment("The one-block-thick floor everyone stands on.") Material floorBlock,
        @Comment("The floor's height. Players stand one block above it.") @Range(min = -63, max = 318) int floorY,
        @Comment({
            "false keeps the world off disk: it starts fresh every time the server does. true",
            "saves it, for builds that should last."})
        boolean autoSave,
        @Comment("Start fresh on every startup even when auto-save is on.") boolean resetOnStartup,
        Border border,
        @Comment("Chunks sent to players in this world, 2 to 32. -1 keeps the server's setting.") @Range(min = -1, max = 32) int viewDistance,
        @Comment("Chunks that tick around players in this world, 2 to 32. -1 keeps the server's setting.") @Range(min = -1, max = 32) int simulationDistance) {

        public World {
            if (!floorBlock.isBlock() || !floorBlock.isSolid() || floorBlock.hasGravity()) {
                throw new IllegalArgumentException("floor-block must be a solid block that doesn't fall, not "
                    + floorBlock.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    public record Border(
        @Comment("The world border's width in blocks. It caps how much world can ever exist.") @Range(min = 64, max = 1_000_000) int size,
        @Comment({
            "Arriving players land up to this many blocks from the centre, so they don't stack.",
            "Small keeps a mass arrival to a handful of chunks; the land around it is kept ready."})
        @Range(min = 0, max = 500_000) int spawnRadius) {}

    public record Player(
        @Comment("Put arriving players in creative.") boolean setCreative,
        @Comment("Give arriving players flight, and keep it on.") boolean enableFlight,
        @Comment({
            "Players can't see each other. With hundreds arriving at once, everyone tracking",
            "everyone else is the biggest load a limbo has; this takes it away."})
        boolean hideOthers) {}

    public record Join(
        @Comment("Hide the \"joined the game\" broadcast.") boolean suppressJoinMessage,
        @Comment("Sent to a player when they arrive. Blank for none.") Message customJoinMessage,
        boolean clearChatOnJoin) {}

    public record Quit(@Comment("Hide the \"left the game\" broadcast.") boolean suppressQuitMessage) {}

    public record Death(@Comment("Hide death messages.") boolean suppressDeathMessage) {}

    public record AutoJoin(
        @Comment("Only without server-check, which sends players back as soon as the main server is up.") boolean enabled,
        @Comment("How long after arriving.") @Range(min = 1, max = 86_400) int delaySeconds,
        @Comment("Sent as a player is sent back. Blank for none.") Message message) {}

    public record ServerCheck(
        boolean enabled,
        @Comment({
            "Your main server's address as xLimbo can reach it (not the proxy's), such as",
            "\"127.0.0.1:25566\". xLimbo asks it for its status, as a server list does."})
        String address,
        @Range(min = 1, max = 300) int intervalSeconds,
        @Comment("Sent to everyone here when the main server comes back up. Blank for none.") Message messageUp) {}

    public record Queue(
        @Comment({
            "How many players are sent back each second, so the main server isn't flooded the",
            "moment it's back. 0 sends everyone at once."})
        @Range(min = 0, max = 1000) int perSecond,
        @Comment({
            "Shown on the action bar while a player waits their turn. <position> and <total>",
            "are filled in. Blank shows the normal action bar instead."})
        Message actionBar) {}

    public record JoinCommand(
        boolean enabled, List<String> aliases, String permission,
        @Comment("Seconds between uses, per player, so nobody can spam your proxy.") @Range(min = 0, max = 3600) int cooldownSeconds,
        @Comment("<server> is main-server.") Message messageSending,
        @Comment("<seconds> is the whole seconds left.") Message messageCooldown) {}

    public record ActionBar(
        boolean enabled,
        @Comment("Ticks between updates (20 is one second).") @Range(min = 1, max = 1200) int intervalTicks,
        Message message) {}

    public record Protections(
        @Comment("The floor can't be broken, pushed, or built under.") boolean floor,
        @Key("void") Void voidProtection,
        @Comment("Nether and End portals, and Eyes of Ender in End portal frames.") boolean portals,
        @Comment("Placing water and lava, and liquids flowing.") boolean liquids,
        @Comment("Sand, gravel, anvils, concrete powder and other blocks that fall.") boolean gravityBlocks,
        @Comment("Fire spreading, burning and being lit.") boolean fire,
        @Comment("Every explosion.") boolean explosions,
        @Comment("Mobs spawning.") boolean mobSpawns,
        @Comment("Redstone and pistons.") boolean redstone,
        @Comment("Dropped items.") boolean itemDrops,
        Entities entities,
        BuildGuard buildGuard) {}

    public record Void(
        @Comment("Put players who fall below threshold-y back on the floor.") boolean enabled,
        @Range(min = -2048, max = 2048) int thresholdY,
        @Comment("No void or fall damage, so a rescue never becomes a death loop.") boolean cancelVoidDamage) {}

    public record Entities(
        @Comment("Armour stands, boats, minecarts, item frames, paintings, End crystals and spawn eggs.") boolean blockEntityItems,
        @Comment("Removes stray entities other than players this often. 0 turns it off.") @Range(min = 0, max = 86_400) int cleanupIntervalSeconds) {}

    public record BuildGuard(
        @Comment("Limits how fast each player can place blocks, against lag machines.") boolean enabled,
        @Range(min = 1, max = 1000) int maxPlacesPerSecond) {}
}
