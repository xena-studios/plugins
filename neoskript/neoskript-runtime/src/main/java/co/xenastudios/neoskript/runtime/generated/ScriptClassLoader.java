/*
 * Copyright (c) 2026 Xena Studios
 * SPDX-License-Identifier: GPL-3.0-only
 */
package co.xenastudios.neoskript.runtime.generated;

/**
 * Defines one compiled class that targets Java 8, where hidden classes don't exist: each gets a loader of its own,
 * so it's unloaded once nothing uses it, as a hidden class is. The class's constants are its static final fields,
 * which its {@code <clinit>} takes from here, once.
 */
public final class ScriptClassLoader extends ClassLoader {

    private Object[] constants;

    public ScriptClassLoader(ClassLoader parent, Object[] constants) {
        super(parent);
        this.constants = constants;
    }

    public Class<?> define(byte[] bytes) {
        return defineClass(null, bytes, 0, bytes.length);
    }

    /** The constants, for the class's {@code <clinit>}: the loader keeps no hold of them afterwards. */
    public synchronized Object[] takeConstants() {
        Object[] taken = constants;
        if (taken == null) {
            throw new IllegalStateException("The constants were taken already");
        }
        constants = null;
        return taken;
    }
}
