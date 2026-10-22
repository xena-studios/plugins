package co.xenastudios.core.text;

import co.xenastudios.core.config.Codec;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * A sound from the config, written as its Minecraft name with an optional volume and
 * pitch: {@code "block.ender_chest.open"} or {@code "block.note_block.pling 0.5 1.5"}.
 * Blank plays nothing. Plays in the Blocks volume, so players can turn it down.
 */
public final class SoundEffect {

    public static final SoundEffect NONE = new SoundEffect("", null);

    public static final Codec<SoundEffect> CODEC = new Codec<>() {
        @Override
        public SoundEffect decode(Object raw) {
            return parse(raw.toString());
        }

        @Override
        public Object encode(SoundEffect value) {
            return value.text;
        }
    };

    private final String text;
    private final Sound sound;

    private SoundEffect(String text, Sound sound) {
        this.text = text;
        this.sound = sound;
    }

    public static SoundEffect parse(String text) {
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts[0].isEmpty()) {
            return NONE;
        }
        if (parts.length > 3) {
            throw new IllegalArgumentException("should be a sound name, then optionally a volume and a pitch");
        }
        Key key;
        try {
            key = Key.key(parts[0]);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("isn't a sound name, such as block.ender_chest.open");
        }
        float volume = parts.length > 1 ? number(parts[1], 0, 10, "a volume from 0 to 10") : 1;
        float pitch = parts.length > 2 ? number(parts[2], 0.5f, 2, "a pitch from 0.5 to 2") : 1;
        return new SoundEffect(text.trim(), Sound.sound(key, Sound.Source.BLOCK, volume, pitch));
    }

    private static float number(String raw, float min, float max, String expected) {
        try {
            float value = Float.parseFloat(raw);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Falls through.
        }
        throw new IllegalArgumentException("should have " + expected + " after the name");
    }

    /**
     * Whether the server knows this sound. A resource pack can add sounds the server
     * doesn't know, so an unknown one is worth a warning, not a refusal.
     */
    public boolean isKnown() {
        if (sound == null) {
            return true;
        }
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).get(sound.name()) != null;
        } catch (RuntimeException | LinkageError e) {
            // No registry to ask, such as in unit tests.
            return true;
        }
    }

    public boolean isNone() {
        return sound == null;
    }

    /** Plays the sound to a player, where they stand. */
    public void play(Audience audience) {
        if (sound != null) {
            audience.playSound(sound, Sound.Emitter.self());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SoundEffect effect && text.equals(effect.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
