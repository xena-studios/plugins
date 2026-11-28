package co.xenastudios.xbackup.remote

import co.xenastudios.xbackup.store.Backend
import co.xenastudios.xbackup.store.Throttle
import co.xenastudios.xbackup.store.readUpTo
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Base64
import java.util.TreeMap
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Lowercase hex of [bytes]. */
internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * S3 and everything that speaks it: AWS, Cloudflare R2, Backblaze B2, Wasabi, MinIO, Hetzner,
 * DigitalOcean Spaces. Requests are signed with AWS Signature Version 4, written here rather than
 * pulling in an SDK, and checked against AWS's published example.
 *
 * Each upload carries its SHA-256 (signed) and its MD5 (`Content-MD5`), so the server refuses a
 * body that arrives damaged. A PUT is all or nothing on S3.
 */
internal class S3Backend(private val name: String, settings: RemoteConfig, private val prefix: String, private val throttle: Throttle) : Backend {
    private val endpoint: String
    private val region: String
    private val bucket: String
    private val pathStyle: Boolean = settings.pathStyle
    private val accessKey: String
    private val secretKey: String

    init {
        val url = Remotes.required(settings.url, "url (the endpoint, such as https://s3.us-east-1.amazonaws.com)", name)
        endpoint = url.removeSuffix("/")
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "url must start with https:// for remote $name." }
        region = settings.region.trim().ifEmpty { "us-east-1" }
        bucket = Remotes.required(settings.bucket, "bucket", name)
        accessKey = Remotes.required(settings.username, "username (the access key id)", name)
        secretKey = Remotes.required(settings.password, "password (the secret key) in secrets.yml", name)
    }

    override fun describe(): String = "S3 bucket $bucket at ${URI.create(endpoint).host}"

    private fun key(file: String): String = if (prefix.isEmpty()) file else "$prefix/$file"

    private fun uri(key: String, query: Map<String, String>): URI {
        val base = URI.create(endpoint)
        val host = base.rawAuthority
        val path = base.rawPath ?: ""
        val full = if (pathStyle) "${base.scheme}://$host$path/${Http.encode(bucket, false)}/${Http.encode(key, true)}"
        else "${base.scheme}://$bucket.$host$path/${Http.encode(key, true)}"
        val q = canonicalQuery(query)
        return URI.create(if (q.isEmpty()) full else "$full?$q")
    }

    // ---- signing ----

    /** Signs a request with SigV4; returns the headers to add. */
    internal fun sign(method: String, uri: URI, headers: Map<String, String>, payloadHash: String, now: ZonedDateTime): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val date = amzDate.substring(0, 8)
        val signed = TreeMap<String, String>()
        for ((key, value) in headers) signed[key.lowercase()] = value.trim()
        val port = uri.port
        val defaultPort = port == -1 || port == 443 && uri.scheme == "https" || port == 80 && uri.scheme == "http"
        signed["host"] = uri.host + if (defaultPort) "" else ":$port"
        signed["x-amz-content-sha256"] = payloadHash
        signed["x-amz-date"] = amzDate
        val canonicalHeaders = StringBuilder()
        for ((key, value) in signed) canonicalHeaders.append(key).append(':').append(value).append('\n')
        val signedHeaders = signed.keys.joinToString(";")
        val canonical = "$method\n${uri.rawPath}\n${uri.rawQuery ?: ""}\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$date/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        var key = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), date)
        key = hmac(key, region)
        key = hmac(key, "s3")
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, toSign))
        return mapOf(
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
            "Authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
        )
    }

    private fun send(method: String, uri: URI, headers: Map<String, String>, payloadHash: String, body: Http.Body? = null): Http.Response =
        Http.send(method, uri, headers + sign(method, uri, headers, payloadHash, ZonedDateTime.now(ZoneOffset.UTC)), body)

    // ---- operations ----

    override fun put(name: String, file: Path) {
        val key = key(Backend.checkName(name))
        val sha = MessageDigest.getInstance("SHA-256")
        val md5 = contentMd5()
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                sha.update(buffer, 0, n)
                md5.update(buffer, 0, n)
            }
        }
        val payload = hex(sha.digest())
        val headers = mapOf("Content-MD5" to Base64.getEncoder().encodeToString(md5.digest()), "Content-Type" to "application/octet-stream")
        Http.retry {
            check("Uploading $name", send("PUT", uri(key, emptyMap()), headers, payload, Http.Body.File(file, throttle)))
        }
    }

    override fun put(name: String, data: ByteArray) {
        val key = key(Backend.checkName(name))
        val headers = mapOf("Content-MD5" to Base64.getEncoder().encodeToString(contentMd5().digest(data)), "Content-Type" to "application/octet-stream")
        val payload = sha256Hex(data)
        Http.retry {
            check("Uploading $name", send("PUT", uri(key, emptyMap()), headers, payload, Http.Body.Bytes(data, throttle)))
        }
    }

    override fun get(name: String): InputStream {
        val key = key(Backend.checkName(name))
        return Http.retry {
            val uri = uri(key, emptyMap())
            val response = Http.download(uri, sign("GET", uri, emptyMap(), EMPTY_SHA256, ZonedDateTime.now(ZoneOffset.UTC)))
            if (response.status == 404) {
                response.body.close()
                throw NoSuchFileException(name)
            }
            if (response.status / 100 != 2) {
                val body = response.body.use { readUpTo(it, 64 * 1024) }
                throw Http.failure("Downloading $name", response.status, errorOf(body))
            }
            response.body
        }
    }

    override fun read(name: String, offset: Long, length: Int): ByteArray {
        val key = key(Backend.checkName(name))
        if (length == 0) return ByteArray(0)
        return Http.retry {
            val response = send("GET", uri(key, emptyMap()), mapOf("Range" to Http.range(offset, length)), EMPTY_SHA256)
            if (response.status == 404) throw NoSuchFileException(name)
            if (response.status == 416) throw Http.ShortFile(name, offset + length)
            check("Reading $name", response)
            Http.ranged(response, name, offset, length)
        }
    }

    override fun list(prefix: String): List<Backend.FileInfo> {
        val files = ArrayList<Backend.FileInfo>()
        val full = key(prefix)
        val cut = if (this.prefix.isEmpty()) "" else "${this.prefix}/"
        var continuation: String? = null
        val base = URI.create(endpoint)
        do {
            val query = TreeMap<String, String>()
            query["list-type"] = "2"
            query["prefix"] = full
            continuation?.let { query["continuation-token"] = it }
            val uri = if (pathStyle) URI.create("$endpoint/${Http.encode(bucket, false)}?${canonicalQuery(query)}")
            else URI.create("${base.scheme}://$bucket.${base.rawAuthority}${base.rawPath ?: ""}/?${canonicalQuery(query)}")
            val response = Http.retry {
                send("GET", uri, emptyMap(), EMPTY_SHA256).also { check("Listing ${describe()}", it) }
            }
            val xml = Http.xml(response.body)
            for (contents in Http.all(xml, "Contents")) {
                val key = Http.text(contents, "Key")
                val size = Http.text(contents, "Size")
                if (key != null && key.startsWith(cut) && !key.endsWith("/")) files += Backend.FileInfo(key.substring(cut.length), size?.toLong() ?: 0)
            }
            continuation = if (Http.text(xml, "IsTruncated") == "true") Http.text(xml, "NextContinuationToken") else null
        } while (continuation != null)
        return files
    }

    override fun delete(name: String) {
        val key = key(Backend.checkName(name))
        Http.retry {
            val response = send("DELETE", uri(key, emptyMap()), emptyMap(), EMPTY_SHA256)
            if (response.status != 404) check("Deleting $name", response)
        }
    }

    private fun check(what: String, response: Http.Response) {
        if (response.status / 100 == 2) return
        val detail = errorOf(response.body)
        if (response.status == 403) {
            throw Http.Permanent("$what on remote $name was refused (HTTP 403${if (detail.isEmpty()) "" else ", $detail"}). Check the keys, the bucket, the region and the key's permissions.")
        }
        throw Http.failure("$what on remote $name", response.status, detail)
    }

    /** Makes the bucket, for tests against a fresh MinIO. */
    internal fun createBucket() {
        val base = URI.create(endpoint)
        val uri = if (pathStyle) URI.create("$endpoint/${Http.encode(bucket, false)}") else URI.create("${base.scheme}://$bucket.${base.rawAuthority}${base.rawPath ?: ""}/")
        Http.retry {
            val response = send("PUT", uri, emptyMap(), EMPTY_SHA256)
            if (response.status != 409) check("Making the bucket", response)
        }
    }

    companion object {
        private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        internal fun canonicalQuery(query: Map<String, String>): String =
            TreeMap(query).entries.joinToString("&") { Http.encode(it.key, false) + "=" + Http.encode(it.value, false) }

        private fun hmac(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

        /**
         * The digest for S3's `Content-MD5` header: a check against damage in transit, which a
         * bucket with Object Lock requires on every upload. Not used for security: each upload's
         * SHA-256 is signed, and every file is named by its own SHA-256.
         */
        private fun contentMd5(): MessageDigest = MessageDigest.getInstance("MD5")

        private fun errorOf(body: ByteArray?): String {
            if (body == null || body.isEmpty()) return ""
            return try {
                val xml = Http.xml(body)
                (Http.text(xml, "Code") ?: "") + (Http.text(xml, "Message")?.let { ": $it" } ?: "")
            } catch (e: java.io.IOException) {
                String(body, Charsets.UTF_8).take(200)
            }
        }
    }
}

/**
 * WebDAV: Nextcloud, ownCloud, Synology, Koofr, Apache and nginx. Uploads go to a temporary name
 * and are then MOVEd into place, so a file is never visible half sent; the size the server
 * reports afterwards is checked against the file's.
 */
internal class WebDavBackend(private val name: String, settings: RemoteConfig, private val throttle: Throttle) : Backend {
    private val base: URI
    private val authorization: String?
    private val madeFolders = HashSet<String>()

    init {
        val url = Remotes.required(settings.url, "url (the folder's WebDAV address)", name)
        require(url.startsWith("https://") || url.startsWith("http://")) { "url must start with https:// for remote $name." }
        val folder = Remotes.cleanPrefix(settings.path)
        base = URI.create((if (url.endsWith("/")) url else "$url/") + if (folder.isEmpty()) "" else Http.encode(folder, true) + "/")
        val user = settings.username.trim()
        authorization = if (user.isEmpty()) null else "Basic " + Base64.getEncoder().encodeToString("$user:${settings.password}".toByteArray(Charsets.UTF_8))
    }

    override fun describe(): String = "WebDAV folder $base"

    private fun uri(file: String): URI = base.resolve(Http.encode(file, true))

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> =
        if (authorization == null) extra else extra + ("Authorization" to authorization)

    override fun put(name: String, file: Path) {
        upload(name, Files.size(file)) { Http.Body.File(file, throttle) }
    }

    override fun put(name: String, data: ByteArray) {
        upload(name, data.size.toLong()) { Http.Body.Bytes(data, throttle) }
    }

    private fun upload(file: String, size: Long, body: () -> Http.Body) {
        Backend.checkName(file)
        makeFolders(file)
        val temp = file + ".part-" + UUID.randomUUID().toString().substring(0, 8)
        try {
            Http.retry {
                check("Uploading $file", Http.send("PUT", uri(temp), headers(mapOf("Content-Type" to "application/octet-stream")), body()))
            }
            // Checked under the temporary name, so a cut-off copy never takes the real one.
            val stored = size(temp)
            if (stored != size) throw java.io.IOException("Remote $name holds $stored bytes of $file after uploading $size; it will be sent again next time.")
            Http.retry {
                check("Moving $file into place", Http.send("MOVE", uri(temp), headers(mapOf("Destination" to uri(file).toString(), "Overwrite" to "T"))))
            }
        } catch (e: Exception) {
            try {
                delete(temp)
            } catch (ignored: Exception) {
                // Listings skip .part- files.
            }
            throw e
        }
    }

    private fun makeFolders(file: String) {
        // The base folder itself, once.
        if (madeFolders.add("")) mkcol(base)
        val slash = file.lastIndexOf('/')
        if (slash < 0) return
        val folder = file.substring(0, slash)
        if (folder in madeFolders) return
        val path = StringBuilder()
        for (part in folder.split('/')) {
            path.append(part).append('/')
            val current = path.toString()
            if (current !in madeFolders) {
                mkcol(base.resolve(Http.encode(current, true)))
                madeFolders += current
            }
        }
        madeFolders += folder
    }

    private fun mkcol(uri: URI) {
        Http.retry {
            val response = Http.send("MKCOL", uri, headers())
            // 405: already there. 301: some servers redirect a folder without its slash.
            if (response.status != 405 && response.status != 301 && response.status / 100 != 2) check("Making the folder ${uri.path}", response)
        }
    }

    private fun propfind(uri: URI, depth: String): Http.Response = Http.retry {
        Http.send("PROPFIND", uri, headers(mapOf("Depth" to depth, "Content-Type" to "application/xml; charset=utf-8")), Http.Body.Bytes(PROPFIND))
    }

    private fun size(file: String): Long {
        val response = propfind(uri(file), "0")
        if (response.status == 404) throw NoSuchFileException(file)
        check("Checking $file", response)
        return Http.text(Http.xml(response.body), "getcontentlength")?.trim()?.toLong() ?: -1
    }

    override fun get(name: String): InputStream = Http.retry {
        val response = Http.download(uri(Backend.checkName(name)), headers())
        if (response.status == 404) {
            response.body.close()
            throw NoSuchFileException(name)
        }
        if (response.status / 100 != 2) {
            response.body.close()
            throw Http.failure("Downloading $name from remote ${this.name}", response.status, null)
        }
        response.body
    }

    override fun read(name: String, offset: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        return Http.retry {
            val response = Http.send("GET", uri(Backend.checkName(name)), headers(mapOf("Range" to Http.range(offset, length))))
            if (response.status == 404) throw NoSuchFileException(name)
            if (response.status == 416) throw Http.ShortFile(name, offset + length)
            check("Reading $name", response)
            Http.ranged(response, name, offset, length)
        }
    }

    override fun list(prefix: String): List<Backend.FileInfo> {
        val files = ArrayList<Backend.FileInfo>()
        val slash = prefix.lastIndexOf('/')
        val folders = ArrayDeque<String>()
        folders += if (slash < 0) "" else prefix.substring(0, slash + 1)
        val basePath = Http.decodePath(base.rawPath)
        while (folders.isNotEmpty()) {
            val folder = folders.poll()
            val response = propfind(if (folder.isEmpty()) base else base.resolve(Http.encode(folder, true)), "1")
            if (response.status == 404) continue
            check("Listing ${describe()}", response)
            for (entry in Http.all(Http.xml(response.body), "response")) {
                val href = Http.text(entry, "href") ?: continue
                val path = Http.decodePath(URI.create(href.trim()).rawPath)
                if (!path.startsWith(basePath)) continue
                val relative = path.substring(basePath.length)
                // The folder listed itself. Following it as "/" would list the server's root.
                if (relative.isEmpty() || relative == "/") continue
                if (Http.all(entry, "collection").isNotEmpty()) {
                    val child = if (relative.endsWith("/")) relative else "$relative/"
                    if (child != folder && child.length > folder.length && child.startsWith(folder)) folders += child
                } else if (relative.startsWith(prefix) && ".part-" !in relative) {
                    files += Backend.FileInfo(relative, Http.text(entry, "getcontentlength")?.trim()?.toLong() ?: 0)
                }
            }
        }
        return files
    }

    override fun delete(name: String) {
        Http.retry {
            val response = Http.send("DELETE", uri(Backend.checkName(name)), headers())
            if (response.status != 404) check("Deleting $name", response)
        }
    }

    private fun check(what: String, response: Http.Response) {
        val status = response.status
        if (status / 100 == 2) return
        if (status == 401 || status == 403) {
            throw Http.Permanent("$what on remote $name was refused (HTTP $status). Check the user name, the password in secrets.yml (Nextcloud: an app password) and the folder's permissions.")
        }
        throw Http.failure("$what on remote $name", status, null)
    }

    private companion object {
        val PROPFIND: ByteArray = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>").toByteArray(Charsets.UTF_8)
    }
}
