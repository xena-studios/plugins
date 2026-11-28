package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.xstats.config.XStatsConfig
import co.xenastudios.xstats.data.PlayerRecord
import co.xenastudios.xstats.store.DbFailed
import co.xenastudios.xstats.store.Q
import co.xenastudios.xstats.store.Store
import co.xenastudios.xstats.web.Json
import co.xenastudios.xstats.web.Page
import co.xenastudios.xstats.web.Pages
import co.xenastudios.xstats.web.Response
import co.xenastudios.xstats.web.Site
import co.xenastudios.xstats.web.WebServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What only the JVM has, for every JVM platform: the website (on the JDK's own HTTP server), its
 * self-test and export, the import from the world's stats files, and the time limit on other plugins'
 * profile sections. Nothing here loads until it's used: the website only while `web.enabled` is on.
 */
public class JvmExtras(
    /** The plugin's folder. */
    private val folder: Path,
    /** The main world's folder, where Minecraft keeps every player's stats file; `null` where there's none (the proxy). */
    private val world: () -> Path?,
    /** The server's folder, with `usercache.json`. */
    private val serverFolder: Path,
    /** A player's last known name, from the server, for the import. Off the server's threads. */
    private val names: (PlayerRef) -> String?,
    /** Where the website's files are, in the jar. */
    private val loader: ClassLoader,
) : Extras {
    @Volatile
    private var server: WebServer? = null

    @Volatile
    private var madePages: Pages? = null

    @Volatile
    private var threads: ExecutorService? = null

    private val importing = AtomicBoolean()

    internal fun pages(plugin: XStatsPlugin): Pages = madePages ?: synchronized(this) {
        madePages ?: Pages(plugin).also {
            it.brandExists = BrandFile(folder.resolve("web"))
            madePages = it
        }
    }

    override val webPort: Int get() = server?.port ?: 0

    // Only once the pages exist: while the website runs, or after its self-test.
    override fun pagesChanged(plugin: XStatsPlugin) {
        madePages?.render()
    }

    override fun webModule(plugin: XStatsPlugin): Module = WebModule(plugin, this)

    private class BrandFile(private val folder: Path) : (String) -> Boolean {
        override fun invoke(name: String): Boolean = Files.isRegularFile(folder.resolve(name))
    }

    /** The website, while `web.enabled` is on. A change to any web setting restarts it on reload. */
    private class WebModule(private val plugin: XStatsPlugin, private val extras: JvmExtras) : Module {
        private var server: WebServer? = null

        override fun enable(context: ModuleContext) {
            val c = plugin.config
            val w = XStatsConfig.web
            try {
                val made = WebServer.start(extras.options(plugin, c[w.bind], c[w.port], c[w.requestsPerMinute]), extras.Source(plugin), extras.loader, extras.warn(plugin))
                server = made
                extras.server = made
                plugin.onDatabase { _ -> extras.pages(plugin).render() }
                val url = c[w.publicUrl].trim()
                plugin.log.info("The stats website is on ${c[w.bind]}:${made.port}" + if (url.isEmpty()) "." else ", at $url.")
            } catch (e: IOException) {
                failed(c, e.toString())
            } catch (e: RuntimeException) {
                failed(c, e.toString())
            } catch (e: NoClassDefFoundError) {
                plugin.webFailed()
                plugin.log.warn("This Java has no built-in web server (jdk.httpserver), so the website can't run. Most Java builds include it; a trimmed-down one may not.")
            }
        }

        private fun failed(c: co.xenastudios.core.common.config.Config, why: String) {
            plugin.webFailed()
            plugin.log.warn(
                "Couldn't start the stats website on ${c[XStatsConfig.web.bind]}:${c[XStatsConfig.web.port]}: $why. " +
                    "Is the port free, and is the address this machine's? Fix web in config.yml (or free the port) and reload.",
            )
        }

        override fun disable() {
            server?.close()
            if (extras.server === server) extras.server = null
            server = null
        }
    }

    internal fun warn(plugin: XStatsPlugin): (String, Throwable?) -> Unit = { message, error -> if (error == null) plugin.log.warn(message) else plugin.log.warn("$message: $error") }

    internal fun options(plugin: XStatsPlugin, bind: String, port: Int, perMinute: Int, test: Boolean = false): WebServer.Options {
        val c = plugin.config
        val w = XStatsConfig.web
        return WebServer.Options(
            bind, port, test || c[w.site], c[w.corsOrigins], perMinute, c[w.maxConcurrent],
            if (test) emptyList() else c[w.trustedProxies], if (test) "x-forwarded-for" else c[w.clientIpHeader],
            c[w.avatars.url], !test && c[w.avatars.proxy],
        )
    }

    /** What the web server serves: the plugin's pages, each made into bytes once per page. */
    internal inner class Source(private val plugin: XStatsPlugin) : WebServer.Source {
        private val pages = pages(plugin)
        private val made = java.util.concurrent.ConcurrentHashMap<String, Pair<Page, Response>>()

        private val history = object : LinkedHashMap<Int, Pair<Long, Store.History>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, Store.History>>?): Boolean = size > 256
        }

        override fun page(path: String): Response? {
            val page = pages.page(path) ?: return null
            made[path]?.let { if (it.first === page) return it.second }
            val response = Response.of(page)
            made[path] = page to response
            return response
        }

        override fun search(query: String?): Response = Response.of(pages.search(query))

        override fun profile(who: String): Response {
            val record = pages.find(who) ?: return Response.of(pages.profile(null, null))
            return Response.of(pages.profile(record, history(record)))
        }

        override fun name(player: PlayerRef): String? = pages.name(player)

        override val brandFolder: Path get() = folder.resolve("web")

        // The player's last 30 days and, on a network, each server's numbers: kept a minute.
        private fun history(record: PlayerRecord): Store.History? {
            val id = record.dbId
            val db = plugin.database ?: return null
            val store = plugin.engine.store ?: return null
            if (id == 0) return null
            synchronized(history) { history[id] }?.let { if (System.currentTimeMillis() - it.first < 60_000) return it.second }
            val from = plugin.engine.today() - Pages.HISTORY_DAYS + 1
            val network = plugin.config[XStatsConfig.network.enabled]
            val read = try {
                db.read { session -> Q.run(session, db.sql) { q -> store.history(q, id, from, network) } }.get(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                return null
            }
            val value = (read as? Outcome.Ok)?.value ?: return null
            synchronized(history) { history[id] = System.currentTimeMillis() to value }
            return value
        }
    }

    // ---- the self-test and the export ----

    private class Check(val method: String, val path: String, val status: Int, val contains: String)

    override fun webTest(plugin: XStatsPlugin): Pair<Int, List<String>> {
        val stat = plugin.catalog.snapshot.stats.firstOrNull { it.website }?.id ?: "none"
        val checks = listOf(
            Check("GET", "/", 200, "<title>"),
            Check("GET", "/app.js", 200, "textContent"),
            Check("GET", "/style.css", 200, "--accent"),
            Check("GET", "/api/v1/server", 200, "\"stats\""),
            Check("GET", "/api/v1/leaderboards/all", 200, "\"boards\""),
            Check("GET", "/api/v1/leaderboard/$stat/all", if (stat == "none") 404 else 200, "\"entries\""),
            Check("GET", "/api/v1/search?q=a", 200, "\"players\""),
            Check("GET", "/api/v1/player/NobodyByThisName", 404, "error"),
            Check("GET", "/../config.yml", 404, ""),
            Check("GET", "/%2e%2e/config.yml", 404, ""),
            Check("POST", "/api/v1/server", 405, ""),
        )
        val test = try {
            // Its pages made now, so the test sees what visitors would.
            val pages = pages(plugin)
            plugin.database?.session { session -> Q.run(session, plugin.database!!.sql) { _ -> pages.render() }.let { Outcome.Ok(Unit) } }?.get(10, TimeUnit.SECONDS)
            WebServer.start(options(plugin, "127.0.0.1", 0, 10_000, test = true), Source(plugin), loader, warn(plugin))
        } catch (e: Exception) {
            return checks.size to listOf("couldn't start a web server: $e")
        } catch (e: NoClassDefFoundError) {
            return checks.size to listOf("this Java has no built-in web server (jdk.httpserver)")
        }
        val failures = ArrayList<String>()
        try {
            for (check in checks) check(test.port, check)?.let(failures::add)
        } finally {
            test.close()
        }
        return checks.size to failures
    }

    private fun check(port: Int, check: Check): String? = try {
        val connection = URL("http://127.0.0.1:$port${check.path}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = check.method
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            if (check.method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
            when {
                status != check.status -> "${check.method} ${check.path} answered $status, not ${check.status}"
                check.contains.isNotEmpty() && check.contains !in body -> "${check.method} ${check.path} didn't contain ${check.contains}"
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        "${check.method} ${check.path}: $e"
    }

    override fun webExport(plugin: XStatsPlugin): Outcome<String, String> = try {
        val site = folder.resolve("site")
        Files.createDirectories(site)
        for (name in Site.FILES.keys) Files.write(site.resolve(name), Site.read(loader, name))
        var api = plugin.config[XStatsConfig.web.publicUrl].trim()
        if (!Regex("https?://[^\\s\"'<>\\\\]+").matches(api)) api = ""
        api = api.trimEnd('/')
        Files.write(site.resolve("config.json"), (Json().obj().field("api", api).end().toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        Outcome.Ok(folder.fileName.toString() + "/site")
    } catch (e: IOException) {
        Outcome.Err(e.toString())
    }

    // ---- other plugins' profile sections, with a time limit ----

    override fun <T> all(tasks: List<() -> T>, millis: Long): List<T?> {
        val pool = threads ?: synchronized(this) {
            threads ?: Executors.newCachedThreadPool { runnable -> Thread(runnable, "xStats sections").also { it.isDaemon = true } }.also { threads = it }
        }
        val asked: List<Future<T>> = tasks.map { task -> pool.submit<T> { task() } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        return asked.map { future ->
            try {
                future.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- the import ----

    override fun importHistory(plugin: XStatsPlugin, firstStart: Boolean, reply: (MessageKey, List<Pair<String, Any>>) -> Unit) {
        if (!importing.compareAndSet(false, true)) return
        try {
            run(plugin, firstStart, reply)
        } catch (e: DbFailed) {
            reply(XStatsMessages.importStopped, listOf("reason" to e.error.detail))
        } catch (e: Exception) {
            reply(XStatsMessages.importStopped, listOf("reason" to e.toString()))
        } finally {
            importing.set(false)
        }
    }

    private fun run(plugin: XStatsPlugin, firstStart: Boolean, reply: (MessageKey, List<Pair<String, Any>>) -> Unit) {
        val world = world()
        val db = plugin.database
        val store = plugin.engine.store
        if (world == null || db == null || store == null) {
            if (!firstStart) reply(XStatsMessages.importNoWorld, emptyList())
            return
        }
        val folders = StatsFiles.folders(world)
        if (folders.isEmpty()) {
            onWriter(plugin) { q -> store.setFlag(q, "imported") }
            reply(XStatsMessages.importNothing, listOf("world" to StatsFiles.levelRoot(world).fileName.toString()))
            return
        }
        if (firstStart) plugin.log.info("First start: reading players' history from the world's stats files.")
        val started = System.currentTimeMillis()
        val cached = StatsFiles.userCache(serverFolder.resolve("usercache.json"))
        val stats = plugin.catalog.snapshot.stats
        val index = StatsFiles.index(folders)
        var added = 0
        var players = 0
        var skipped = index.skipped
        // What's in memory goes in first, so the import never adds a row a save is about to write.
        onWriter(plugin) { q -> plugin.engine.save(q, System.currentTimeMillis()) }
        var from = 0
        while (from < index.files.size) {
            val part = StatsFiles.readPart(index.files.subList(from, minOf(index.files.size, from + CHUNK)), stats) { id -> cached[id] ?: names(id) }
            skipped += part.skipped
            players += part.players.size
            added += onWriter(plugin) { q ->
                val count = store.importRows(q, part.players)
                // In the same step, so no save between writes back the lower value it held.
                plugin.engine.raiseImported(part.players)
                count
            }
            from += CHUNK
        }
        onWriter(plugin) { q ->
            store.setFlag(q, "imported")
            plugin.engine.afterImport(q)
            plugin.afterSave()
        }
        val skippedWord: Any = if (skipped > 0) Texts.Word(XStatsMessages.importSkipped, "count" to skipped) else co.xenastudios.core.common.text.Component.EMPTY
        reply(
            XStatsMessages.importDone,
            listOf("count" to added, "players" to players, "files" to index.files.size, "millis" to System.currentTimeMillis() - started, "skipped" to skippedWord),
        )
    }

    // Runs work on the database's writer and waits for it, its error thrown.
    private fun <T> onWriter(plugin: XStatsPlugin, work: (Q) -> T): T {
        val db = plugin.database ?: throw IOException("the database isn't open")
        val result = db.session { session -> Outcome.Ok(Q.run(session, db.sql, work)) }.get(120, TimeUnit.SECONDS)
        return when (val ran = (result as Outcome.Ok).value) {
            is Outcome.Ok -> ran.value
            is Outcome.Err -> throw DbFailed(ran.error)
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
