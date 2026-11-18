package co.xenastudios.xplots.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A plot's rental: who, until when, and who else may build. Main thread only. */
public final class Lease {

    /** Warnings sent for this lease: none, the day before, the hour before. */
    public static final int WARNED_NONE = 0;
    public static final int WARNED_DAY = 1;
    public static final int WARNED_HOUR = 2;

    private final UUID owner;
    private String ownerName;
    private long expires;
    private int slots;
    private int warned;
    private final Map<UUID, String> members = new LinkedHashMap<>();

    public Lease(UUID owner, String ownerName, long expires, int slots, int warned) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.expires = expires;
        this.slots = slots;
        this.warned = warned;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void ownerName(String name) {
        this.ownerName = name;
    }

    /** When it runs out, in Unix milliseconds. */
    public long expires() {
        return expires;
    }

    public void expires(long expires) {
        this.expires = expires;
    }

    public long remaining(long now) {
        return Math.max(0, expires - now);
    }

    /** How many members it may have, besides the owner. */
    public int slots() {
        return slots;
    }

    public void slots(int slots) {
        this.slots = slots;
    }

    public int warned() {
        return warned;
    }

    public void warned(int warned) {
        this.warned = warned;
    }

    /** Members' uuids and their names as last seen, in the order they were added. */
    public Map<UUID, String> members() {
        return members;
    }

    /** Whether the player may build here: the owner or a member. */
    public boolean trusts(UUID player) {
        return owner.equals(player) || members.containsKey(player);
    }

    /** Which warning is due with this much left: the hour's, the day's, or none. */
    public static int warningFor(long remaining) {
        if (remaining <= 3_600_000L) {
            return WARNED_HOUR;
        }
        return remaining <= 86_400_000L ? WARNED_DAY : WARNED_NONE;
    }
}
