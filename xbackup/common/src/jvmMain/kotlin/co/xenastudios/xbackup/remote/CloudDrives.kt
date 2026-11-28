package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Json
import co.xenastudios.xbackup.store.Throttle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Linking the cloud drives. Google Drive and OneDrive use the OAuth device flow: xBackup shows a
 * short code, the owner enters it on the provider's site from any device, and xBackup gets a
 * refresh token. Dropbox has no device flow, so it's the PKCE code flow with no redirect: the
 * owner opens a link, approves, and pastes the code Dropbox shows into `/backup link`. The tokens
 * are kept in `tokens.yml`, readable by its owner only, never in config.yml and never backed up.
 */
public object OAuth {
    /** A provider's endpoints. Tests point them at a local server. */
    public class Endpoints(public val deviceCode: String?, public val token: String, public val authorize: String?, public val scope: String?)

    @JvmField
    public val ENDPOINTS: MutableMap<String, Endpoints> = ConcurrentHashMap(
        mapOf(
            "drive" to Endpoints("https://oauth2.googleapis.com/device/code", "https://oauth2.googleapis.com/token", null, "https://www.googleapis.com/auth/drive.file"),
            "onedrive" to Endpoints(
                "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode", "https://login.microsoftonline.com/common/oauth2/v2.0/token", null,
                "Files.ReadWrite.AppFolder offline_access",
            ),
            "dropbox" to Endpoints(null, "https://api.dropboxapi.com/oauth2/token", "https://www.dropbox.com/oauth2/authorize", null),
        ),
    )

    private val LOCK = Any()
    private const val TOKENS_HEADER = "Cloud drive tokens from /backup link, by remote. Only the server's user can read this file,\nand it's never backed up. Delete a remote's lines to unlink it."

    // The tokens, as "remote.key" names over tokens.yml's two levels.
    private class Tokens(val values: MutableMap<String, MutableMap<String, String>>) {
        operator fun get(name: String): String? = values[name.substringBeforeLast('.')]?.get(name.substringAfterLast('.'))

        operator fun set(name: String, value: String) {
            values.getOrPut(name.substringBeforeLast('.')) { LinkedHashMap() }[name.substringAfterLast('.')] = value
        }

        fun remove(name: String): String? = values[name.substringBeforeLast('.')]?.remove(name.substringAfterLast('.'))
    }

    private fun file(home: Path): Path = home.resolve(Remotes.TOKENS)

    private fun load(home: Path): Tokens {
        val file = file(home)
        val legacy = home.resolve(Remotes.LEGACY_TOKENS)
        if (!Files.exists(file) && Files.exists(legacy)) {
            // From one of 1.x's first builds: moved over once, then the old file goes.
            val old = Properties()
            StringReader(String(Files.readAllBytes(legacy), Charsets.UTF_8)).use { old.load(it) }
            val moved = Tokens(LinkedHashMap())
            for (name in old.stringPropertyNames()) if (name.indexOf('.') > 0) moved[name] = old.getProperty(name)
            save(home, moved)
            Files.delete(legacy)
            return moved
        }
        return Tokens(SecretFile.read(file))
    }

    private fun save(home: Path, tokens: Tokens) {
        Files.createDirectories(home)
        SecretFile.write(file(home), TOKENS_HEADER, tokens.values)
    }

    /** Whether the remote has been linked. */
    @JvmStatic
    public fun linked(home: Path, remote: String): Boolean = synchronized(LOCK) { load(home)["$remote.refresh-token"] != null }

    /** A current access token for the remote, refreshed with its refresh token when it's about to run out. */
    internal fun accessToken(home: Path, remote: String, type: String, settings: RemoteConfig): String = synchronized(LOCK) {
        val tokens = load(home)
        val refresh = tokens["$remote.refresh-token"] ?: throw Http.Permanent("remote $remote isn't linked yet. Run /backup link $remote.")
        val access = tokens["$remote.access-token"]
        val expires = tokens["$remote.expires"]?.toLongOrNull() ?: 0
        if (access != null && System.currentTimeMillis() < expires - 60_000) return access
        val form = linkedMapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(remote, settings))
        if (type == "drive") form["client_secret"] = settings.clientSecret.trim()
        if (type == "onedrive") form["scope"] = ENDPOINTS.getValue(type).scope!!
        val reply = post(ENDPOINTS.getValue(type).token, form)
        val error = Json.string(reply, "error")
        if (error != null) {
            if (error == "invalid_grant") throw Http.Permanent("remote $remote's link was revoked or expired. Run /backup link $remote again.")
            throw java.io.IOException("remote $remote: refreshing its token failed ($error)")
        }
        store(tokens, remote, reply)
        save(home, tokens)
        tokens["$remote.access-token"]!!
    }

    /** Drops the cached access token, so the next request gets a fresh one. */
    internal fun expire(home: Path, remote: String) {
        synchronized(LOCK) {
            val tokens = load(home)
            if (tokens.remove("$remote.expires") != null) save(home, tokens)
        }
    }

    private fun store(tokens: Tokens, remote: String, reply: Map<String, Any?>) {
        val access = Json.string(reply, "access_token") ?: throw java.io.IOException("the provider's reply had no access token")
        tokens["$remote.access-token"] = access
        tokens["$remote.expires"] = (System.currentTimeMillis() + Json.number(reply, "expires_in", 3600) * 1000).toString()
        Json.string(reply, "refresh_token")?.let { tokens["$remote.refresh-token"] = it }
    }

    private fun clientId(remote: String, settings: RemoteConfig): String =
        Remotes.required(settings.clientId, "client-id (from the app you registered; the docs show how)", remote)

    // ---- device flow ----

    /** What the owner is shown: where to go and the code to enter. */
    public class DeviceCode(public val userCode: String?, public val url: String?, public val deviceCode: String?, public val interval: Int, public val expiresAt: Long)

    /** Starts a device-flow link for Google Drive or OneDrive. */
    @JvmStatic
    public fun start(remote: String, type: String, settings: RemoteConfig): DeviceCode {
        val endpoints = ENDPOINTS.getValue(type)
        val reply = post(endpoints.deviceCode!!, linkedMapOf("client_id" to clientId(remote, settings), "scope" to endpoints.scope!!))
        Json.string(reply, "error")?.let { throw java.io.IOException("the provider refused to start linking ($it: ${Json.string(reply, "error_description")}). Check client-id.") }
        val url = Json.string(reply, "verification_url") ?: Json.string(reply, "verification_uri")
        return DeviceCode(
            Json.string(reply, "user_code"), url, Json.string(reply, "device_code"), Json.number(reply, "interval", 5).toInt(),
            System.currentTimeMillis() + Json.number(reply, "expires_in", 900) * 1000,
        )
    }

    /** Waits for the owner to enter the code, then keeps the refresh token. Blocks: run it off the game threads. */
    @JvmStatic
    public fun finish(home: Path, remote: String, type: String, settings: RemoteConfig, code: DeviceCode, cancelled: () -> Boolean) {
        val endpoints = ENDPOINTS.getValue(type)
        var interval = maxOf(1, code.interval) * 1000L
        while (System.currentTimeMillis() < code.expiresAt) {
            if (cancelled()) throw java.io.IOException("stopped")
            try {
                Thread.sleep(interval)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("stopped")
            }
            val form = linkedMapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId(remote, settings), "device_code" to (code.deviceCode ?: ""))
            if (type == "drive") form["client_secret"] = settings.clientSecret.trim()
            val reply = post(endpoints.token, form)
            when (val error = Json.string(reply, "error")) {
                null -> {
                    synchronized(LOCK) {
                        val tokens = load(home)
                        store(tokens, remote, reply)
                        if (tokens["$remote.refresh-token"] == null) throw java.io.IOException("the provider gave no refresh token; check the app's settings")
                        save(home, tokens)
                    }
                    return
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5000
                "access_denied", "authorization_declined" -> throw java.io.IOException("linking was declined.")
                "expired_token" -> throw java.io.IOException("the code ran out before it was entered. Try again.")
                else -> throw java.io.IOException("linking failed ($error: ${Json.string(reply, "error_description")})")
            }
        }
        throw java.io.IOException("the code ran out before it was entered. Try again.")
    }

    // ---- Dropbox ----

    /** The link the owner opens to approve Dropbox; remembers the PKCE verifier for [dropboxCode]. */
    @JvmStatic
    public fun dropboxUrl(home: Path, remote: String, settings: RemoteConfig): String {
        val random = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        synchronized(LOCK) {
            val tokens = load(home)
            tokens["$remote.verifier"] = verifier
            save(home, tokens)
        }
        return ENDPOINTS.getValue("dropbox").authorize + "?client_id=" + Http.encode(clientId(remote, settings), false) +
            "&response_type=code&token_access_type=offline&code_challenge_method=S256&code_challenge=" + challenge
    }

    /** Swaps the code Dropbox showed for a refresh token. */
    @JvmStatic
    public fun dropboxCode(home: Path, remote: String, settings: RemoteConfig, code: String) {
        synchronized(LOCK) {
            val tokens = load(home)
            val verifier = tokens["$remote.verifier"] ?: throw java.io.IOException("start with /backup link $remote to get the link first.")
            val reply = post(
                ENDPOINTS.getValue("dropbox").token,
                linkedMapOf("grant_type" to "authorization_code", "code" to code.trim(), "client_id" to clientId(remote, settings), "code_verifier" to verifier),
            )
            Json.string(reply, "error")?.let {
                throw java.io.IOException("Dropbox refused the code ($it: ${Json.string(reply, "error_description")}). Codes work once and soon run out; run /backup link $remote again.")
            }
            store(tokens, remote, reply)
            tokens.remove("$remote.verifier")
            save(home, tokens)
        }
    }

    private fun post(url: String, form: Map<String, String>): Map<String, Any?> {
        val uri = URI.create(url)
        val response = Http.retry {
            val r = Http.send("POST", uri, mapOf("Content-Type" to "application/x-www-form-urlencoded"), Http.Body.Bytes(Http.form(form)))
            if (r.status >= 500) throw Http.Transient("HTTP ${r.status} from ${uri.host}")
            r
        }
        return try {
            Json.obj(response.text)
        } catch (e: java.io.IOException) {
            throw java.io.IOException("HTTP ${response.status} from ${uri.host} with a reply that isn't JSON")
        }
    }
}

/**
 * Google Drive, OneDrive and Dropbox, over their REST APIs with OAuth tokens from `/backup link`.
 * Each keeps the repository in a folder only xBackup's app can see: Drive's `drive.file` scope,
 * OneDrive's app folder, Dropbox's app folder.
 */
internal object CloudDrives {
    /** API base URLs. Tests point them at a local server. */
    val BASES: MutableMap<String, String> = ConcurrentHashMap(
        mapOf(
            "drive" to "https://www.googleapis.com",
            "onedrive" to "https://graph.microsoft.com/v1.0",
            "dropbox-api" to "https://api.dropboxapi.com",
            "dropbox-content" to "https://content.dropboxapi.com",
        ),
    )

    fun open(name: String, type: String, settings: RemoteConfig, prefix: String, context: Remotes.Context): Backend {
        Remotes.required(settings.clientId, "client-id (from the app you registered; the docs show how)", name)
        val folder = prefix.ifEmpty { "xbackup" }
        return when (type) {
            "drive" -> Drive(name, settings, folder, context)
            "onedrive" -> OneDrive(name, settings, folder, context)
            else -> Dropbox(name, settings, folder, context)
        }
    }

    /** Throws if the drive says it holds a different number of bytes than were sent. */
    fun checkSize(remote: String, file: String, stored: Long, sent: Long) {
        if (stored >= 0 && stored != sent) throw java.io.IOException("remote $remote holds $stored bytes of $file after uploading $sent; it will be sent again next time.")
    }

    @Suppress("UNCHECKED_CAST")
    fun cast(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: throw java.io.IOException("unexpected reply from the drive")

    /** What the three share: a token that refreshes itself, and one retry after a 401. */
    abstract class Cloud(val name: String, val type: String, val settings: RemoteConfig, val folder: String, val context: Remotes.Context) : Backend {
        val throttle: Throttle = context.upload

        fun auth(extra: Map<String, String> = emptyMap()): Map<String, String> =
            extra + ("Authorization" to "Bearer " + OAuth.accessToken(context.home, name, type, settings))

        fun send(what: String, method: String, url: String, headers: Map<String, String> = emptyMap(), body: Http.Body? = null): Http.Response = Http.retry {
            var response = Http.send(method, URI.create(url), auth(headers), body)
            if (response.status == 401) {
                // The token was revoked early or clocks differ: one fresh token, then give up.
                OAuth.expire(context.home, name)
                response = Http.send(method, URI.create(url), auth(headers), body)
            }
            if (response.status >= 500 || response.status == 429) throw Http.Transient("$what on remote $name failed: HTTP ${response.status}")
            response
        }

        fun failed(what: String, response: Http.Response): java.io.IOException {
            val body = response.text.take(300)
            if (response.status == 401 || response.status == 403) {
                return Http.Permanent("$what on remote $name was refused (HTTP ${response.status}). Run /backup link $name again. $body")
            }
            return Http.failure("$what on remote $name", response.status, body)
        }

        override fun put(name: String, data: ByteArray) {
            val temp = Files.createTempFile("xbackup", ".part")
            try {
                Files.write(temp, data)
                put(name, temp)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        override fun get(name: String): InputStream = ByteArrayInputStream(download(name, -1, 0))

        override fun read(name: String, offset: Long, length: Int): ByteArray = if (length == 0) ByteArray(0) else download(name, offset, length)

        /** The file's bytes; the whole file when [offset] is -1. */
        abstract fun download(file: String, offset: Long, length: Int): ByteArray

        fun range(offset: Long, length: Int): Map<String, String> = if (offset >= 0) mapOf("Range" to Http.range(offset, length)) else emptyMap()
    }

    /**
     * Google Drive, v3. The app only sees files it made (`drive.file`), so everything goes in one
     * folder it makes, named after `path`, each file named by its repository path. Drive finds
     * files by id, so names are looked up once per backend and remembered.
     */
    class Drive(name: String, settings: RemoteConfig, folder: String, context: Remotes.Context) : Cloud(name, "drive", settings, folder, context) {
        private var folderId: String? = null
        private var ids: MutableMap<String, String>? = null
        private var sizes: MutableMap<String, Long> = HashMap()

        // Drive allows several files with one name (an upload whose old copy wasn't deleted before
        // a crash). The newest is used; the others are removed with it.
        private var duplicates: MutableMap<String, MutableList<String>> = HashMap()

        override fun describe(): String = "Google Drive folder $folder"

        private val base: String get() = BASES.getValue("drive")

        private fun q(text: String): String = text.replace("\\", "\\\\").replace("'", "\\'")

        @Synchronized
        private fun folderId(): String {
            folderId?.let { return it }
            val query = "name = '${q(folder)}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val response = send("Finding the folder", "GET", "$base/drive/v3/files?q=${Http.encode(query, false)}&fields=${Http.encode("files(id)", false)}&spaces=drive&orderBy=createdTime")
            if (response.status != 200) throw failed("Finding the folder", response)
            val files = Json.obj(response.text)["files"] as? List<*>
            if (!files.isNullOrEmpty()) {
                // More than one folder by that name: always the oldest, the one copies went to first.
                return Json.string(cast(files[0]), "id")!!.also { folderId = it }
            }
            val body = Json.write(mapOf("name" to folder, "mimeType" to "application/vnd.google-apps.folder"))
            val created = send("Making the folder", "POST", "$base/drive/v3/files?fields=id", mapOf("Content-Type" to "application/json"), Http.Body.Bytes(body.toByteArray(Charsets.UTF_8)))
            if (created.status != 200) throw failed("Making the folder", created)
            return Json.string(Json.obj(created.text), "id")!!.also { folderId = it }
        }

        @Synchronized
        private fun index(): MutableMap<String, String> {
            ids?.let { return it }
            val found = HashMap<String, String>()
            val foundSizes = HashMap<String, Long>()
            val extra = HashMap<String, MutableList<String>>()
            val parent = folderId()
            var page: String? = null
            do {
                val query = "'$parent' in parents and trashed = false"
                val url = "$base/drive/v3/files?q=${Http.encode(query, false)}&pageSize=1000&orderBy=createdTime&fields=" +
                    Http.encode("nextPageToken,files(id,name,size)", false) + (page?.let { "&pageToken=" + Http.encode(it, false) } ?: "")
                val response = send("Listing", "GET", url)
                if (response.status != 200) throw failed("Listing", response)
                val reply = Json.obj(response.text)
                for (item in reply["files"] as? List<*> ?: emptyList<Any>()) {
                    val file = cast(item)
                    val fileName = Json.string(file, "name") ?: continue
                    val older = found.put(fileName, Json.string(file, "id") ?: continue)
                    if (older != null) extra.getOrPut(fileName) { ArrayList() } += older
                    foundSizes[fileName] = Json.string(file, "size")?.toLongOrNull() ?: 0
                }
                page = Json.string(reply, "nextPageToken")
            } while (page != null)
            ids = found
            sizes = foundSizes
            duplicates = extra
            return found
        }

        @Synchronized
        override fun put(name: String, file: Path) {
            Backend.checkName(name)
            val ids = index()
            val metadata = Json.write(mapOf("name" to name, "parents" to listOf(folderId())))
            val session = send(
                "Starting the upload of $name", "POST", "$base/upload/drive/v3/files?uploadType=resumable&fields=id,size",
                mapOf("Content-Type" to "application/json; charset=UTF-8", "X-Upload-Content-Type" to "application/octet-stream"), Http.Body.Bytes(metadata.toByteArray(Charsets.UTF_8)),
            )
            val location = session.header("Location")
            if (session.status != 200 || location == null) throw failed("Starting the upload of $name", session)
            val size = Files.size(file)
            val uploaded = send("Uploading $name", "PUT", location, emptyMap(), Http.Body.File(file, throttle))
            if (uploaded.status != 200 && uploaded.status != 201) throw failed("Uploading $name", uploaded)
            val reply = Json.obj(uploaded.text)
            val id = Json.string(reply, "id") ?: throw java.io.IOException("remote ${this.name}: Drive gave no id for $name")
            val stored = Json.string(reply, "size")?.toLongOrNull() ?: -1
            if (stored >= 0 && stored != size) {
                deleteId(id)
                throw java.io.IOException("remote ${this.name} holds $stored bytes of $name after uploading $size; it will be sent again next time.")
            }
            val old = ids.put(name, id)
            sizes[name] = size
            val stale = ArrayList(duplicates.remove(name).orEmpty())
            if (old != null && old != id) stale += old
            for (staleId in stale) deleteId(staleId)
        }

        @Synchronized
        override fun download(file: String, offset: Long, length: Int): ByteArray {
            val id = index()[Backend.checkName(file)] ?: throw NoSuchFileException(file)
            val response = send("Downloading $file", "GET", "$base/drive/v3/files/$id?alt=media", range(offset, length))
            if (response.status == 404) throw NoSuchFileException(file)
            if (response.status != 200 && response.status != 206) throw failed("Downloading $file", response)
            return if (offset < 0) response.body else Http.ranged(response, file, offset, length)
        }

        @Synchronized
        override fun list(prefix: String): List<Backend.FileInfo> = index().keys.filter { it.startsWith(prefix) }.map { Backend.FileInfo(it, sizes[it] ?: 0) }

        @Synchronized
        override fun delete(name: String) {
            val id = index().remove(Backend.checkName(name))
            sizes.remove(name)
            val all = ArrayList(duplicates.remove(name).orEmpty())
            if (id != null) all += id
            for (each in all) deleteId(each)
        }

        private fun deleteId(id: String) {
            val response = send("Deleting", "DELETE", "$base/drive/v3/files/$id")
            if (response.status != 204 && response.status != 200 && response.status != 404) throw failed("Deleting", response)
        }
    }

    /** OneDrive, through Microsoft Graph, in the app's own folder (`Apps/<app name>`). */
    class OneDrive(name: String, settings: RemoteConfig, folder: String, context: Remotes.Context) : Cloud(name, "onedrive", settings, folder, context) {
        override fun describe(): String = "OneDrive app folder $folder"

        private fun item(file: String): String = BASES.getValue("onedrive") + "/me/drive/special/approot:/" + Http.encode("$folder/$file", true) + ":"

        override fun put(name: String, file: Path) {
            Backend.checkName(name)
            val size = Files.size(file)
            if (size < SIMPLE_UPLOAD) {
                // Small files, empty ones (pins) included, in one simple PUT: an upload session can't
                // carry an empty file (its Content-Range would be "bytes 0--1/0").
                val uploaded = send("Uploading $name", "PUT", item(name) + "/content?@microsoft.graph.conflictBehavior=replace", mapOf("Content-Type" to "application/octet-stream"), Http.Body.File(file, throttle))
                if (uploaded.status != 200 && uploaded.status != 201) throw failed("Uploading $name", uploaded)
                checkSize(this.name, name, Json.number(Json.obj(uploaded.text), "size", -1), size)
                return
            }
            val body = "{\"item\":{\"@microsoft.graph.conflictBehavior\":\"replace\"}}"
            val session = send("Starting the upload of $name", "POST", item(name) + "/createUploadSession", mapOf("Content-Type" to "application/json"), Http.Body.Bytes(body.toByteArray(Charsets.UTF_8)))
            if (session.status != 200) throw failed("Starting the upload of $name", session)
            val url = Json.string(Json.obj(session.text), "uploadUrl") ?: throw failed("Starting the upload of $name", session)
            // One request for the whole file: packs are well under the 60 MiB a request may carry.
            // The upload URL carries its own authorisation: no token goes with it.
            val uploaded = Http.retry { Http.send("PUT", URI.create(url), mapOf("Content-Range" to "bytes 0-${size - 1}/$size"), Http.Body.File(file, throttle)) }
            if (uploaded.status != 200 && uploaded.status != 201) throw failed("Uploading $name", uploaded)
            checkSize(this.name, name, Json.number(Json.obj(uploaded.text), "size", -1), size)
        }

        override fun download(file: String, offset: Long, length: Int): ByteArray {
            val response = send("Downloading $file", "GET", item(Backend.checkName(file)) + "/content", range(offset, length))
            if (response.status == 404) throw NoSuchFileException(file)
            if (response.status != 200 && response.status != 206) throw failed("Downloading $file", response)
            return if (offset < 0) response.body else Http.ranged(response, file, offset, length)
        }

        override fun list(prefix: String): List<Backend.FileInfo> {
            val files = ArrayList<Backend.FileInfo>()
            val slash = prefix.lastIndexOf('/')
            val folders = ArrayDeque<String>()
            folders += if (slash < 0) "" else prefix.substring(0, slash)
            while (folders.isNotEmpty()) {
                val current = folders.poll()
                val path = if (current.isEmpty()) folder else "$folder/$current"
                var url: String? = BASES.getValue("onedrive") + "/me/drive/special/approot:/" + Http.encode(path, true) + ":/children?\$select=name,size,folder&\$top=1000"
                while (url != null) {
                    val response = send("Listing", "GET", url)
                    if (response.status == 404) break
                    if (response.status != 200) throw failed("Listing", response)
                    val reply = Json.obj(response.text)
                    for (value in reply["value"] as? List<*> ?: emptyList<Any>()) {
                        val entry = cast(value)
                        val child = Json.string(entry, "name") ?: continue
                        val relative = if (current.isEmpty()) child else "$current/$child"
                        if (entry.containsKey("folder")) folders += relative else if (relative.startsWith(prefix)) files += Backend.FileInfo(relative, Json.number(entry, "size", 0))
                    }
                    url = Json.string(reply, "@odata.nextLink")
                }
            }
            return files
        }

        override fun delete(name: String) {
            val response = send("Deleting $name", "DELETE", item(Backend.checkName(name)))
            if (response.status != 204 && response.status != 200 && response.status != 404) throw failed("Deleting $name", response)
        }

        companion object {
            /** Graph takes files under 4 MiB in a simple PUT; bigger ones need an upload session. */
            const val SIMPLE_UPLOAD: Long = 4L shl 20
        }
    }

    /** Dropbox, API v2, in the app's folder (`Apps/<app name>`). */
    class Dropbox(name: String, settings: RemoteConfig, folder: String, context: Remotes.Context) : Cloud(name, "dropbox", settings, folder, context) {
        override fun describe(): String = "Dropbox app folder $folder"

        private fun path(file: String): String = "/$folder/$file"

        override fun put(name: String, file: Path) {
            Backend.checkName(name)
            val size = Files.size(file)
            // Dropbox-API-Arg must be ASCII; Json.write escapes everything else.
            val args = Json.write(linkedMapOf("path" to path(name), "mode" to "overwrite", "autorename" to false, "mute" to true))
            val response = send(
                "Uploading $name", "POST", BASES.getValue("dropbox-content") + "/2/files/upload",
                mapOf("Dropbox-API-Arg" to args, "Content-Type" to "application/octet-stream"), Http.Body.File(file, throttle),
            )
            if (response.status != 200) throw failed("Uploading $name", response)
            checkSize(this.name, name, Json.number(Json.obj(response.text), "size", -1), size)
        }

        override fun download(file: String, offset: Long, length: Int): ByteArray {
            val response = send(
                "Downloading $file", "POST", BASES.getValue("dropbox-content") + "/2/files/download",
                mapOf("Dropbox-API-Arg" to Json.write(mapOf("path" to path(Backend.checkName(file))))) + range(offset, length), Http.Body.Bytes(ByteArray(0)),
            )
            if (response.status == 409 && "not_found" in response.text) throw NoSuchFileException(file)
            if (response.status != 200 && response.status != 206) throw failed("Downloading $file", response)
            return if (offset < 0) response.body else Http.ranged(response, file, offset, length)
        }

        override fun list(prefix: String): List<Backend.FileInfo> {
            val files = ArrayList<Backend.FileInfo>()
            val root = "/$folder"
            var url = BASES.getValue("dropbox-api") + "/2/files/list_folder"
            var body = Json.write(linkedMapOf("path" to root, "recursive" to true, "limit" to 2000))
            while (true) {
                val response = send("Listing", "POST", url, mapOf("Content-Type" to "application/json"), Http.Body.Bytes(body.toByteArray(Charsets.UTF_8)))
                if (response.status == 409 && "not_found" in response.text) return files
                if (response.status != 200) throw failed("Listing", response)
                val reply = Json.obj(response.text)
                for (value in reply["entries"] as? List<*> ?: emptyList<Any>()) {
                    val entry = cast(value)
                    if (Json.string(entry, ".tag") != "file") continue
                    val display = Json.string(entry, "path_display")
                    if (display == null || display.length <= root.length + 1) continue
                    val relative = display.substring(root.length + 1)
                    if (relative.startsWith(prefix)) files += Backend.FileInfo(relative, Json.number(entry, "size", 0))
                }
                if (reply["has_more"] != true) return files
                url = BASES.getValue("dropbox-api") + "/2/files/list_folder/continue"
                body = Json.write(mapOf("cursor" to Json.string(reply, "cursor")))
            }
        }

        override fun delete(name: String) {
            val body = Json.write(mapOf("path" to path(Backend.checkName(name))))
            val response = send("Deleting $name", "POST", BASES.getValue("dropbox-api") + "/2/files/delete_v2", mapOf("Content-Type" to "application/json"), Http.Body.Bytes(body.toByteArray(Charsets.UTF_8)))
            if (response.status == 409 && "not_found" in response.text) return
            if (response.status != 200) throw failed("Deleting $name", response)
        }
    }
}
