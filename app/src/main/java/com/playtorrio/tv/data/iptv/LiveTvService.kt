package com.playtorrio.tv.data.iptv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Free live-TV channels from the public iptv-org project
 * (github.com/iptv-org/iptv). Category playlists are plain M3U over HTTPS, so
 * we fetch + parse one on demand and cache it in memory. Gives the app a
 * ready-to-watch Live TV browser with categories out of the box — no portal
 * setup required.
 */
object LiveTvService {

    private const val TAG = "LiveTv"

    data class Channel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
    )

    data class Category(val label: String, val slug: String, val adult: Boolean = false)

    /** Every iptv-org category, no exceptions. `adult` ones are hidden unless
     *  the 18+ content setting is enabled. */
    val CATEGORIES: List<Category> = listOf(
        Category("Sports", "sports"),
        Category("News", "news"),
        Category("Movies", "movies"),
        Category("Series", "series"),
        Category("Music", "music"),
        Category("Entertainment", "entertainment"),
        Category("Documentary", "documentary"),
        Category("Kids", "kids"),
        Category("Comedy", "comedy"),
        Category("General", "general"),
        Category("Animation", "animation"),
        Category("Family", "family"),
        Category("Culture", "culture"),
        Category("Science", "science"),
        Category("Lifestyle", "lifestyle"),
        Category("Cooking", "cooking"),
        Category("Travel", "travel"),
        Category("Outdoor", "outdoor"),
        Category("Relax", "relax"),
        Category("Classic", "classic"),
        Category("Auto", "auto"),
        Category("Business", "business"),
        Category("Shop", "shop"),
        Category("Religious", "religious"),
        Category("Education", "education"),
        Category("Legislative", "legislative"),
        Category("Weather", "weather"),
        Category("XXX", "xxx", adult = true),
    )

    private val cache = mutableMapOf<String, List<Channel>>()

    private val extinfRe = Regex("""#EXTINF:-?\d+([^,]*),(.*)""")
    private val logoRe = Regex("""tvg-logo="([^"]*)"""")
    private val groupRe = Regex("""group-title="([^"]*)"""")

    /** Quick liveness probe for a channel stream URL. Alive = a 2xx/3xx within
     *  the timeout. Runs on IO. */
    suspend fun isAlive(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Range", "bytes=0-1")
                connectTimeout = 4_000; readTimeout = 4_000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            conn.inputStream.use { runCatching { it.read() } }
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) { false }
    }

    /** Probe [channels] with bounded concurrency; returns the URLs found dead. */
    suspend fun findDead(channels: List<Channel>, concurrency: Int = 12): Set<String> {
        val dead = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        channels.chunked(concurrency).forEach { batch ->
            coroutineScope {
                batch.map { ch ->
                    async(Dispatchers.IO) { if (!isAlive(ch.url)) dead.add(ch.url) }
                }.awaitAll()
            }
        }
        return dead.toSet()
    }

    suspend fun channels(categorySlug: String): List<Channel> = withContext(Dispatchers.IO) {
        cache[categorySlug]?.let { return@withContext it }
        val out = mutableListOf<Channel>()
        try {
            val url = "https://iptv-org.github.io/iptv/categories/$categorySlug.m3u"
            val text = URL(url).openConnection().apply {
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 12_000; readTimeout = 20_000
            }.getInputStream().bufferedReader().use { it.readText() }

            var pendingName: String? = null
            var pendingLogo: String? = null
            var pendingGroup: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#EXTINF")) {
                    val m = extinfRe.find(line)
                    val attrs = m?.groupValues?.get(1).orEmpty()
                    pendingName = m?.groupValues?.get(2)?.trim().orEmpty().ifBlank { "Channel" }
                    pendingLogo = logoRe.find(attrs)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    pendingGroup = groupRe.find(attrs)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                } else if (line.isNotEmpty() && !line.startsWith("#")) {
                    val name = pendingName
                    if (name != null && (line.startsWith("http"))) {
                        out.add(Channel(name, line, pendingLogo, pendingGroup))
                    }
                    pendingName = null; pendingLogo = null; pendingGroup = null
                }
            }
            cache[categorySlug] = out
            Log.i(TAG, "Loaded ${out.size} channels for $categorySlug")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $categorySlug: ${e.message}")
        }
        out
    }
}
