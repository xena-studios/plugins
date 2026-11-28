package co.xenastudios.xlimbo

/** `server-check.address`, read: `host:port`, `[ipv6]:port`, or a host alone for the default port. */
public sealed class StatusAddress {
    public class Ok(public val host: String, public val port: Int) : StatusAddress()

    public class Bad(public val why: String) : StatusAddress()

    public companion object {
        public const val DEFAULT_PORT: Int = 25565

        public fun parse(address: String): StatusAddress {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return Bad("is blank")
            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close < 0) return Bad("\"$address\" is missing a ]")
                val host = trimmed.substring(1, close)
                val rest = trimmed.substring(close + 1)
                if (rest.isEmpty()) return Ok(host, DEFAULT_PORT)
                if (!rest.startsWith(":")) return Bad("\"$address\" has a port that isn't a number")
                return port(address, host, rest.substring(1))
            }
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.indexOf(':') == colon) return port(address, trimmed.substring(0, colon), trimmed.substring(colon + 1))
            return Ok(trimmed, DEFAULT_PORT)
        }

        private fun port(address: String, host: String, text: String): StatusAddress {
            val port = text.toIntOrNull() ?: return Bad("\"$address\" has a port that isn't a number")
            if (port !in 1..65535) return Bad("\"$address\" has a port outside 1 to 65535")
            return Ok(host, port)
        }
    }
}

/**
 * Asks a Minecraft server for its status, as the multiplayer screen does. A server only answers
 * once it's running, not while it's still loading, so an answer means players can join. Blocking:
 * xLimbo calls it off the server's threads. On the JVM, `JvmStatusPing`.
 */
public fun interface StatusPinger {
    public fun isUp(host: String, port: Int): Boolean
}
