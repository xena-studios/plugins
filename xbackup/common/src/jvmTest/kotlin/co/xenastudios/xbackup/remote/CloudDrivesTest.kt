package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.Fixtures
import co.xenastudios.xbackup.backup.Backup
import co.xenastudios.xbackup.restore.Restore
import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Json
import co.xenastudios.xbackup.store.LocalBackend
import co.xenastudios.xbackup.store.Repository
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.readAll
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Random
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Google Drive, OneDrive and Dropbox against a local stand-in for each one's API, as 1.x's
 * CloudDrivesTest: linking (the device flow, Dropbox's code), tokens refreshed and kept owner-only,
 * every backend operation, and a copy restored from the drive.
 */
class CloudDrivesTest {
    private val dir: Path = Files.createTempDirectory("xbackup-drives")
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base: String
    private val files = TreeMap<String, ByteArray>()
    private val polls = AtomicInteger()
    private val refreshes = AtomicInteger()
    private val sessions = HashMap<String, String>()
    private var folderMade = false

    init {
        server.createContext("/") { exchange ->
            try {
                synchronized(files) { handle(exchange) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        OAuth.ENDPOINTS["drive"] = OAuth.Endpoints("$base/device", "$base/token", null, "drive.file")
        OAuth.ENDPOINTS["onedrive"] = OAuth.Endpoints("$base/device", "$base/token", null, "Files.ReadWrite.AppFolder offline_access")
        OAuth.ENDPOINTS["dropbox"] = OAuth.Endpoints(null, "$base/token", "$base/authorize", null)
        CloudDrives.BASES["drive"] = base
        CloudDrives.BASES["onedrive"] = "$base/graph"
        CloudDrives.BASES["dropbox-api"] = "$base/dbapi"
        CloudDrives.BASES["dropbox-content"] = "$base/dbcontent"
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    // ---- the stand-in

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        val query = exchange.requestURI.rawQuery
        val body = exchange.requestBody.readBytes()
        val method = exchange.requestMethod
        if (path != "/device" && path != "/token" && !path.startsWith("/session/") && !path.startsWith("/graph/upload/")) {
            val auth = exchange.requestHeaders.getFirst("Authorization")
            if (auth == null || !auth.startsWith("Bearer access-")) return reply(exchange, 401, "{\"error\":\"unauthorized\"}")
        }
        when {
            path == "/device" -> reply(exchange, 200, "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\",\"verification_url\":\"https://example/device\",\"interval\":1,\"expires_in\":60}")
            path == "/token" -> {
                val form = String(body)
                when {
                    "grant_type=refresh_token" in form -> reply(exchange, 200, "{\"access_token\":\"access-${refreshes.incrementAndGet()}\",\"expires_in\":3600}")
                    "device_code" in form && polls.getAndIncrement() == 0 -> reply(exchange, 400, "{\"error\":\"authorization_pending\"}")
                    "code_verifier" in form && "code=right" !in form -> reply(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad code\"}")
                    // Already expired, so the first request refreshes it.
                    else -> reply(exchange, 200, "{\"access_token\":\"access-0\",\"refresh_token\":\"refresh\",\"expires_in\":0}")
                }
            }
            path.startsWith("/drive/v3/files") || path.startsWith("/upload/drive/v3/files") || path.startsWith("/session/") -> drive(exchange, method, path, query, body)
            path.startsWith("/graph/") -> oneDrive(exchange, method, path, body)
            path.startsWith("/dbapi/") || path.startsWith("/dbcontent/") -> dropbox(exchange, path, body)
            else -> reply(exchange, 404, "{}")
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, json: String) = reply(exchange, status, json.toByteArray())

    private fun reply(exchange: HttpExchange, status: Int, bytes: ByteArray) {
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    private fun content(exchange: HttpExchange, data: ByteArray) {
        val range = exchange.requestHeaders.getFirst("Range") ?: return reply(exchange, 200, data)
        val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range) ?: return reply(exchange, 416, ByteArray(0))
        val from = m.groupValues[1].toInt()
        val to = minOf(m.groupValues[2].toInt(), data.size - 1)
        reply(exchange, 206, data.copyOfRange(from, to + 1))
    }

    // Drive: ids are "f" + name; the folder is "folder".
    private fun drive(exchange: HttpExchange, method: String, path: String, query: String?, body: ByteArray) {
        val q = if (query == null) "" else URLDecoder.decode(query, "UTF-8")
        when {
            path == "/drive/v3/files" && method == "GET" -> {
                if ("mimeType = 'application/vnd.google-apps.folder'" in q) return reply(exchange, 200, if (folderMade) "{\"files\":[{\"id\":\"folder\"}]}" else "{\"files\":[]}")
                val out = files.entries.joinToString(",") { "{\"id\":${Json.quote("f" + it.key)},\"name\":${Json.quote(it.key)},\"size\":\"${it.value.size}\"}" }
                reply(exchange, 200, "{\"files\":[$out]}")
            }
            path == "/drive/v3/files" && method == "POST" -> {
                folderMade = true
                reply(exchange, 200, "{\"id\":\"folder\"}")
            }
            path == "/upload/drive/v3/files" && method == "POST" -> {
                val name = Json.string(Json.obj(String(body)), "name")!!
                val id = "s${sessions.size}"
                sessions[id] = name
                exchange.responseHeaders.add("Location", "$base/session/$id")
                reply(exchange, 200, ByteArray(0))
            }
            path.startsWith("/session/") -> {
                val name = sessions.getValue(path.substring(9))
                files[name] = body
                reply(exchange, 200, "{\"id\":${Json.quote("f$name")},\"size\":\"${body.size}\"}")
            }
            path.startsWith("/drive/v3/files/") -> {
                val name = URLDecoder.decode(path.substring(16), "UTF-8").substring(1)
                when {
                    method == "DELETE" -> reply(exchange, if (files.remove(name) == null) 404 else 204, ByteArray(0))
                    name in files -> content(exchange, files.getValue(name))
                    else -> reply(exchange, 404, "{}")
                }
            }
        }
    }

    private fun oneDrive(exchange: HttpExchange, method: String, path: String, body: ByteArray) {
        if (path.startsWith("/graph/upload/")) {
            // Graph wants a valid Content-Range on an upload session: an empty file must go up
            // with a simple PUT instead.
            val range = exchange.requestHeaders.getFirst("Content-Range")
            if (range == null || !range.matches(Regex("bytes 0-\\d+/\\d+")) || body.isEmpty()) return reply(exchange, 400, "{\"error\":{\"code\":\"invalidRange\"}}")
            files[URLDecoder.decode(path.substring(14), "UTF-8")] = body
            return reply(exchange, 201, "{\"size\":${body.size}}")
        }
        val rest = URLDecoder.decode(path.substring("/graph/me/drive/special/approot:/".length), "UTF-8")
        val colon = rest.indexOf(':')
        val item = rest.substring(0, colon)
        val action = rest.substring(colon + 1)
        when {
            action == "/createUploadSession" -> reply(exchange, 200, "{\"uploadUrl\":${Json.quote("$base/graph/upload/" + Http.encode(item, true))}}")
            action == "/content" && method == "PUT" -> {
                if (body.size > 4 shl 20) return reply(exchange, 413, "{}")
                files[item] = body
                reply(exchange, 201, "{\"size\":${body.size}}")
            }
            action == "/content" -> if (item in files) content(exchange, files.getValue(item)) else reply(exchange, 404, "{}")
            action == "/children" -> {
                val folders = sortedSetOf<String>()
                val out = ArrayList<String>()
                for ((key, value) in files) {
                    if (!key.startsWith("$item/")) continue
                    val below = key.substring(item.length + 1)
                    val slash = below.indexOf('/')
                    if (slash >= 0) folders += below.substring(0, slash) else out += "{\"name\":${Json.quote(below)},\"size\":${value.size}}"
                }
                for (folder in folders) out += "{\"name\":${Json.quote(folder)},\"folder\":{}}"
                reply(exchange, 200, "{\"value\":[${out.joinToString(",")}]}")
            }
            action.isEmpty() && method == "DELETE" -> reply(exchange, if (files.remove(item) == null) 404 else 204, ByteArray(0))
            else -> reply(exchange, 400, "{}")
        }
    }

    private fun dropbox(exchange: HttpExchange, path: String, body: ByteArray) {
        val arg = exchange.requestHeaders.getFirst("Dropbox-API-Arg")
        when (path) {
            "/dbcontent/2/files/upload" -> {
                files[Json.string(Json.obj(arg), "path")!!] = body
                reply(exchange, 200, "{\"size\":${body.size}}")
            }
            "/dbcontent/2/files/download" -> {
                val data = files[Json.string(Json.obj(arg), "path")]
                if (data == null) reply(exchange, 409, "{\"error_summary\":\"path/not_found/\"}") else content(exchange, data)
            }
            "/dbapi/2/files/list_folder" -> {
                val root = Json.string(Json.obj(String(body)), "path")!!
                val out = files.entries.filter { it.key.startsWith("$root/") }
                    .joinToString(",") { "{\".tag\":\"file\",\"path_display\":${Json.quote(it.key)},\"size\":${it.value.size}}" }
                reply(exchange, 200, "{\"entries\":[$out],\"has_more\":false,\"cursor\":\"c\"}")
            }
            "/dbapi/2/files/delete_v2" -> {
                val target = Json.string(Json.obj(String(body)), "path")
                if (files.remove(target) == null) reply(exchange, 409, "{\"error_summary\":\"path_lookup/not_found/\"}") else reply(exchange, 200, "{}")
            }
            else -> reply(exchange, 404, "{}")
        }
    }

    // ---- the tests

    private fun settings(type: String) = RemoteConfig(type, true, "xbackup", "", "", "", false, "", "", "", 0, "", "", "explicit", "client", "secret")

    private fun linked(type: String): Backend {
        val home = dir.resolve("home")
        Files.createDirectories(home)
        val settings = settings(type)
        if (type == "dropbox") {
            val url = OAuth.dropboxUrl(home, type, settings)
            assertTrue(url.startsWith("$base/authorize?client_id=client&response_type=code"), url)
            assertFailsWith<IOException> { OAuth.dropboxCode(home, type, settings, "wrong") }
            OAuth.dropboxCode(home, type, settings, "right")
        } else {
            val code = OAuth.start(type, type, settings)
            assertEquals("ABCD-EFGH", code.userCode)
            OAuth.finish(home, type, type, settings, code) { false }
        }
        assertTrue(OAuth.linked(home, type))
        return Remotes.open(type, settings, Remotes.Context(home, Throttle.NONE, {}))
    }

    @Test
    fun tokensFromAnEarlierBuildMoveToTheOwnerOnlyFile() {
        val home = dir.resolve("legacy")
        Files.createDirectories(home)
        Files.write(home.resolve(Remotes.LEGACY_TOKENS), "gdrive.refresh-token=abc\ngdrive.expires=0\n".toByteArray())
        assertTrue(OAuth.linked(home, "gdrive"))
        assertTrue(!Files.exists(home.resolve(Remotes.LEGACY_TOKENS)))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(Remotes.TOKENS))))
        assertEquals("abc", SecretFile.read(home.resolve(Remotes.TOKENS))["gdrive"]?.get("refresh-token"))
    }

    @Test
    fun drive() = linkThenPutGetListReadDelete("drive")

    @Test
    fun oneDrive() = linkThenPutGetListReadDelete("onedrive")

    @Test
    fun dropbox() = linkThenPutGetListReadDelete("dropbox")

    private fun linkThenPutGetListReadDelete(type: String) {
        linked(type).use { backend ->
            val data = ByteArray(200_000).also(Random(5)::nextBytes)
            backend.put("data/ab/one", data)
            backend.put("index/two", data)
            backend.put("index/two", data)
            // A pin is an empty file.
            val pin = "keep/" + "ab".repeat(32)
            backend.put(pin, ByteArray(0))
            val big = ByteArray(5 shl 20).also(Random(6)::nextBytes)
            backend.put("data/cd/big", big)
            assertEquals(
                setOf(Backend.FileInfo("data/ab/one", 200_000), Backend.FileInfo("index/two", 200_000), Backend.FileInfo(pin, 0), Backend.FileInfo("data/cd/big", 5L shl 20)),
                backend.list("").toSet(),
            )
            assertContentEquals(big, backend.readAll("data/cd/big"))
            backend.delete(pin)
            backend.delete("data/cd/big")
            assertEquals(1, backend.list("index/").size)
            assertContentEquals(data, backend.readAll("data/ab/one"))
            assertContentEquals(data.copyOfRange(10, 110), backend.read("index/two", 10, 100))
            assertFailsWith<NoSuchFileException> { backend.read("data/ab/missing", 0, 1) }
            backend.delete("data/ab/one")
            backend.delete("data/ab/one")
            assertEquals(1, backend.list("").size)
            // The first token came back already expired, so it was refreshed once, and kept.
            assertEquals(1, refreshes.get())
        }
    }

    @Test
    fun copyThenRestoreFromDrive() = copyThenRestore("drive")

    @Test
    fun copyThenRestoreFromOneDrive() = copyThenRestore("onedrive")

    @Test
    fun copyThenRestoreFromDropbox() = copyThenRestore("dropbox")

    private fun copyThenRestore(type: String) {
        val root = Fixtures.server(dir.resolve("server"))
        val snapshot = Repository.open(LocalBackend(dir.resolve("repo")), dir.resolve("temp"), true).use { repository ->
            linked(type).use { remote ->
                val snapshot = repository.lock(true).use { Backup.run(repository, Fixtures.plan(root, dir.resolve("repo")), Backup.Pause.None, Fixtures.sqlite, { false }) {}.snapshot }
                Mirror.push(repository, remote, { false }) {}
                assertEquals(0, Mirror.push(repository, remote, { false }) {}.filesSent)
                Fetch.metadata(remote, dir.resolve("cache")) {}.use { cached ->
                    val items = Restore.expand(cached, snapshot, listOf("world"), emptySet())
                    Fetch.data(remote, cached, items) {}
                    Restore.write(cached, items, dir.resolve("restored"))
                }
                snapshot
            }
        }
        val expected = Fixtures.files(root.resolve("world")).mapValues { (path, bytes) -> if (path.endsWith(".mca")) Fixtures.live(root.resolve("world").resolve(path)) else bytes }
        val actual = Fixtures.files(dir.resolve("restored/world")).mapValues { (path, bytes) -> if (path.endsWith(".mca")) Fixtures.live(dir.resolve("restored/world").resolve(path)) else bytes }
        assertEquals(expected.keys, actual.keys, "backup ${snapshot.key}")
        for ((path, bytes) in expected) assertContentEquals(bytes, actual[path], path)
    }
}
