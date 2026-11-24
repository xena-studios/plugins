package co.xenastudios.core.common.update

// Pumpkin's plugins have no system properties: its adapter decides whether to check.
internal actual fun offlineProperty(): Boolean = false
