package co.xenastudios.core.config;

import org.bukkit.configuration.ConfigurationSection;

/** Carries a config forward one version, editing the raw YAML in place. */
@FunctionalInterface
public interface Migration {
    void apply(ConfigurationSection root);
}
