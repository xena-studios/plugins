package co.xenastudios.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The modules that are on, turned on and off by key as the config changes. */
final class ModuleSet {

    private final Logger logger;
    private final Map<String, Module> active = new LinkedHashMap<>();

    ModuleSet(Logger logger) {
        this.logger = logger;
    }

    void sync(Map<String, Supplier<Module>> wanted) {
        for (String key : Map.copyOf(active).keySet()) {
            if (!wanted.containsKey(key)) {
                disable(key, active.remove(key));
            }
        }
        wanted.forEach((key, factory) -> {
            if (!active.containsKey(key)) {
                try {
                    Module module = factory.get();
                    module.enable();
                    active.put(key, module);
                } catch (RuntimeException | LinkageError e) {
                    // A feature that can't start is skipped; the rest of the plugin runs.
                    logger.log(Level.SEVERE, "Couldn't turn on " + key + "; it stays off", e);
                }
            }
        });
    }

    void clear() {
        sync(Map.of());
    }

    int size() {
        return active.size();
    }

    private void disable(String key, Module module) {
        try {
            module.disable();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.SEVERE, "Couldn't turn off " + key + " cleanly", e);
        }
    }
}
