package co.xenastudios.m0.hello

import co.xenastudios.m0.ClickAction
import co.xenastudios.m0.Component
import co.xenastudios.m0.Configs
import co.xenastudios.m0.Durations
import co.xenastudios.m0.Markup
import co.xenastudios.m0.Parsed
import co.xenastudios.m0.SpikeConfig
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import plugin.PluginMetadata
import plugin.PumpkinPlugin
import pumpkin.Common
import pumpkin.Context
import pumpkin.Ipc
import pumpkin.Logging
import pumpkin.Text
import pumpkin.runtime.ComponentException

/**
 * M0 spike: the shared `common` module running inside Pumpkin. On load it parses a duration and
 * markup with `common`, draws the markup into Pumpkin's text, tries to round-trip the config
 * through a file in its data folder (WASI), and asks xena-m0-peer to parse a duration over IPC.
 */
class HelloPlugin : PumpkinPlugin() {
    override fun metadata() = PluginMetadata(
        name = "xena-m0-hello",
        version = "0.0.0",
        authors = listOf("Xena Studios"),
        description = "M0 spike: shared Kotlin common code on Pumpkin",
        // Loads after the peer, so IPC to it works from onLoad.
        dependencies = listOf("xena-m0-peer"),
        // fs.write.data preopens plugins/data/<name> as "data" for WASI file access.
        permissions = listOf("fs.write.data"),
    )

    private fun log(message: String) = Logging.log(Logging.Level.INFO, "[m0-hello] $message")

    override fun onLoad(context: Context.Context): Result<Unit> {
        return try { load(context) } catch (e: Throwable) {
            // pumpkin-api-kt's export bridge casts a failure to ComponentException; any other
            // exception type there becomes a ClassCastException and an uncatchable trap.
            Result.failure(ComponentException("onLoad threw: $e"))
        }
    }

    private fun load(context: Context.Context): Result<Unit> {
        log("Hello from Kotlin/Wasm on Pumpkin")
        try { error("boom") } catch (e: IllegalStateException) { log("Kotlin exceptions are catchable: ${e.message}") }

        when (val d = Durations.parse("1d2h30m")) {
            is Parsed.Ok -> log("Durations.parse(\"1d2h30m\") = ${d.value} (${d.value.inWholeMilliseconds} ms), formatted back: ${Durations.format(d.value)}")
            is Parsed.Err -> log("duration failed: ${d.error}")
        }

        val markup = "<gold>Welcome, <bold>Steve</bold>!</gold> <click:run_command:/spawn><hover:show_text:'<green>Go'><u>[spawn]"
        val tree = Markup.parse(markup)
        log("Markup.parse plain: ${tree.plain()}")
        val text = draw(tree)
        log("drawn to Pumpkin text (console): ${text.toPrettyConsole()}")
        log("drawn to Pumpkin text (json): ${text.toJson()}")

        val dataFolder = context.getDataFolder()
        log("data folder (host path): $dataFolder")
        try {
            val path = Path("data/config.json")
            SystemFileSystem.sink(path).buffered().use { it.writeString(Configs.encode(SpikeConfig(language = "de"))) }
            val read = SystemFileSystem.source(path).buffered().use { it.readString() }
            when (val c = Configs.decode(read)) {
                is Parsed.Ok -> log("config round-tripped through WASI file data/config.json: language=${c.value.language}, storage=${c.value.storage.type}, bytes=${read.length}")
                is Parsed.Err -> log("config decode failed: ${c.error.reason}")
            }
        } catch (e: Throwable) {
            // The WASI preview1 adapter allocates through Kotlin's cabi_realloc, after which Kotlin
            // refuses new scoped allocators, so stdlib and kotlinx-io WASI calls fail inside every
            // pumpkin-api-kt callback; see RESULTS.md. Free that memory so host calls work again.
            @OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class, kotlin.wasm.unsafe.ComponentModelInternalApi::class)
            kotlin.wasm.unsafe.freeAllComponentModelReallocAllocatedMemory()
            Logging.log(Logging.Level.WARN, "[m0-hello] WASI file access failed: $e")
        }

        val reply = Ipc.sendIpcMessage("xena-m0-peer", "1d2h30m".encodeToByteArray().map { it.toUByte() })
        val answer = reply.fold(
            onSuccess = { inner -> inner.fold({ bytes -> bytes.map { it.toByte() }.toByteArray().decodeToString() }, { "recipient error: $it" }) },
            onFailure = { "send failed: $it" },
        )
        log("IPC to xena-m0-peer replied: $answer")

        return Result.success(Unit)
    }

    override fun onUnload(context: Context.Context): Result<Unit> {
        log("unloaded")
        return Result.success(Unit)
    }

    /** Draws our component tree to Pumpkin's text, as platform-pumpkin would. */
    private fun draw(c: Component): Text.TextComponent {
        val out = Text.TextComponent.text(c.text)
        val s = c.style
        s.color?.let { out.colorRgb(Common.RgbColor((it.rgb shr 16 and 0xFF).toUByte(), (it.rgb shr 8 and 0xFF).toUByte(), (it.rgb and 0xFF).toUByte())) }
        s.bold?.let(out::bold)
        s.italic?.let(out::italic)
        s.underlined?.let(out::underlined)
        s.strikethrough?.let(out::strikethrough)
        s.obfuscated?.let(out::obfuscated)
        when (val click = s.click) {
            is ClickAction.RunCommand -> out.clickRunCommand(click.value)
            is ClickAction.SuggestCommand -> out.clickSuggestCommand(click.value)
            is ClickAction.OpenUrl -> out.clickOpenUrl(click.value)
            is ClickAction.CopyToClipboard -> out.clickCopyToClipboard(click.value)
            null -> Unit
        }
        s.hover?.let { out.hoverShowText(draw(it)) }
        c.children.forEach { out.addChild(draw(it)) }
        return out
    }
}
