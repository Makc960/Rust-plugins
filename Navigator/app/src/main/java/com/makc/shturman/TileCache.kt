package com.makc.shturman

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The map kept on the phone. Every tile, letter and icon of the scheme the page loads goes to disk
 * and comes from there next time: the map shows at once on start, and works without the internet
 * where it was seen. Satellite pictures are kept for a while too. Areas fetched for the road
 * ([prefetch]) lie apart, in the app's own files, and are never cleared to make room.
 *
 * WebView asks [intercept] on its own background threads, several at a time.
 */
class TileCache(context: Context, private val onProgress: (tag: String, done: Int, total: Int, failed: Int) -> Unit) {
    private val cacheRoot = File(context.cacheDir, "tiles")
    private val offlineRoot = File(context.filesDir, "offline")
    private val downloads = Executors.newFixedThreadPool(4)
    private val housekeeping = Executors.newSingleThreadExecutor()
    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()
    private val writes = AtomicInteger()

    fun handles(uri: Uri): Boolean = uri.scheme == "https" && uri.host in HOSTS

    /** [uri] from disk, or from the network and kept; null leaves the request to WebView. */
    fun intercept(uri: Uri): WebResourceResponse? {
        val key = keyOf(uri) ?: return null
        val kept = find(key)
        val index = key.endsWith("planet.json")
        if (kept != null && !index && fresh(kept)) return respond(kept.readBytes(), key, 200)
        // The index names the tiles of the current week: asked anew whenever the network answers.
        val answer = fetch(uri.toString(), if (index) 4000 else 9000)
        if (answer != null && answer.first == 200) {
            store(File(cacheRoot, key), answer.second)
            return respond(answer.second, key, 200)
        }
        if (kept != null) return respond(kept.readBytes(), key, 200)
        if (answer != null) return respond(answer.second, key, answer.first)
        return respond(ByteArray(0), key, 504)
    }

    /**
     * Fetches [urls] in the background, kept for good ([keep], an area for the road) or as the
     * map seen ([keep] false, the road ahead of a drive). Progress goes to [onProgress].
     */
    fun prefetch(tag: String, urls: List<String>, keep: Boolean) {
        val stop = AtomicBoolean(false)
        cancelled[tag]?.set(true)
        cancelled[tag] = stop
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val total = urls.size
        val root = if (keep) offlineRoot else cacheRoot
        onProgress(tag, 0, total, 0)
        for (url in urls) {
            downloads.execute {
                if (stop.get()) return@execute
                val key = keyOf(Uri.parse(url))
                var ok = false
                if (key != null) {
                    val there = File(root, key)
                    if (there.exists() && fresh(there)) {
                        ok = true
                    } else {
                        val answer = fetch(url, 15000)
                        if (answer != null && (answer.first == 200 || answer.first == 204 || answer.first == 404)) {
                            if (answer.first == 200) store(there, answer.second)
                            ok = true
                        }
                    }
                }
                val d = if (ok) done.incrementAndGet() else done.get()
                val f = if (ok) failed.get() else failed.incrementAndGet()
                if ((d + f) % 20 == 0 || d + f == total) onProgress(tag, d, total, f)
            }
        }
    }

    fun cancel(tag: String) {
        cancelled[tag]?.set(true)
    }

    /** Megabytes kept: {cache, offline}. */
    fun sizes(): Pair<Long, Long> = Pair(sizeOf(cacheRoot) / MB, sizeOf(offlineRoot) / MB)

    /** Forgets "cache", "offline" or "all". */
    fun clear(which: String) {
        housekeeping.execute {
            if (which == "cache" || which == "all") cacheRoot.deleteRecursively()
            if (which == "offline" || which == "all") offlineRoot.deleteRecursively()
        }
    }

    fun release() {
        for (stop in cancelled.values) stop.set(true)
        downloads.shutdownNow()
        housekeeping.shutdown()
    }

    // ---- Disk ----

    private fun find(key: String): File? {
        val offline = File(offlineRoot, key)
        if (offline.exists()) return offline
        val cached = File(cacheRoot, key)
        return if (cached.exists()) cached else null
    }

    private fun fresh(file: File): Boolean {
        val age = System.currentTimeMillis() - file.lastModified()
        return age < if (file.path.startsWith(offlineRoot.path)) OFFLINE_AGE else CACHE_AGE
    }

    private fun store(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp" + Thread.currentThread().id)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(MainActivity.TAG, "could not keep a tile", e)
        }
        if (writes.incrementAndGet() % 400 == 0) housekeeping.execute { trim() }
    }

    /** The map seen is kept up to [MAX_CACHE]; past it, the oldest go. */
    private fun trim() {
        val files = cacheRoot.walkTopDown().filter { it.isFile }.toMutableList()
        var size = files.sumOf { it.length() }
        if (size <= MAX_CACHE) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (size <= MAX_CACHE * 8 / 10) break
            size -= f.length()
            f.delete()
        }
    }

    private fun sizeOf(dir: File): Long = if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- Network ----

    /** (status, body), or null when nothing answered. */
    private fun fetch(url: String, timeout: Int): Pair<Int, ByteArray>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", Bridge.USER_AGENT)
        val status = c.responseCode
        val stream = if (status >= 400) c.errorStream else c.inputStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        Pair(status, body)
    } catch (e: Exception) {
        null
    }

    private fun respond(bytes: ByteArray, key: String, status: Int): WebResourceResponse {
        val type = when (key.substringAfterLast('.', "")) {
            "pbf" -> "application/x-protobuf"
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            404 -> "Not Found"
            504 -> "Offline"
            else -> "Error"
        }
        // A 204 has no body: as an empty 200, the map takes it for an empty tile.
        val code = if (status == 204) 200 else status
        return WebResourceResponse(type, null, code, reason, mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val MAX_CACHE = 500L * MB
        private const val CACHE_AGE = 14L * 24 * 3600 * 1000
        private const val OFFLINE_AGE = 90L * 24 * 3600 * 1000
        private val HOSTS = setOf("tiles.openfreemap.org", "server.arcgisonline.com")
        private val SAFE = Regex("[^A-Za-z0-9._@-]")

        /**
         * Where [uri] is kept, under the cache or offline folder, or null for what is not kept.
         * OpenFreeMap's tiles move to a new folder every week; kept without it, a tile fetched
         * once still serves after the move.
         */
        fun keyOf(uri: Uri): String? {
            val parts = uri.pathSegments.map { SAFE.replace(it, "_") }.filter { it.isNotEmpty() && it != "." && it != ".." }
            if (parts.isEmpty()) return null
            return when (uri.host) {
                "tiles.openfreemap.org" -> when {
                    parts == listOf("planet") -> "ofm/planet.json"
                    parts[0] == "planet" && parts.size == 5 -> "ofm/planet/${parts[2]}/${parts[3]}/${parts[4]}"
                    parts[0] == "fonts" || parts[0] == "sprites" || parts[0] == "natural_earth" -> "ofm/" + parts.joinToString("/")
                    else -> null
                }
                "server.arcgisonline.com" -> {
                    val i = parts.indexOf("tile")
                    if (i < 0 || parts.size != i + 4) null else "esri/${parts[i + 1]}/${parts[i + 2]}/${parts[i + 3]}.jpg"
                }
                else -> null
            }
        }
    }
}
