package co.xenastudios.core.common.update

// The tests' switch, as for today's plugins: -Dxena.offline=true keeps the update checker and
// bStats from reaching the network.
internal actual fun offlineProperty(): Boolean = java.lang.Boolean.getBoolean("xena.offline")
