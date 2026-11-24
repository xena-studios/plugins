package co.xenastudios.m0.peer

import co.xenastudios.m0.Durations
import co.xenastudios.m0.Parsed
import plugin.PluginMetadata
import plugin.PumpkinPlugin
import pumpkin.Context
import pumpkin.Logging

/** The other end of the IPC test: answers a duration string with its length in milliseconds. */
class PeerPlugin : PumpkinPlugin() {
    override fun metadata() = PluginMetadata(
        name = "xena-m0-peer",
        version = "0.0.0",
        authors = listOf("Xena Studios"),
        description = "M0 spike: answers IPC calls from xena-m0-hello",
        dependencies = emptyList(),
        permissions = emptyList(),
    )

    override fun onLoad(context: Context.Context): Result<Unit> {
        Logging.log(Logging.Level.INFO, "[m0-peer] loaded, waiting for IPC")
        return Result.success(Unit)
    }

    override fun handleIpcMessage(sender: String, message: List<UByte>): Result<List<UByte>> {
        val text = message.map { it.toByte() }.toByteArray().decodeToString()
        val reply = when (val d = Durations.parse(text)) {
            is Parsed.Ok -> "ok:${d.value.inWholeMilliseconds}"
            is Parsed.Err -> "err:${d.error.reason}"
        }
        Logging.log(Logging.Level.INFO, "[m0-peer] IPC from $sender: '$text' -> '$reply'")
        return Result.success(reply.encodeToByteArray().map { it.toUByte() })
    }
}
