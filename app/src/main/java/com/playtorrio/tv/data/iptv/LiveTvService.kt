package com.playtorrio.tv.data.iptv

import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    /** iptv-org category slugs offered in the UI (label → slug). */
    val CATEGORIES: List<Pair<String, String>> = listOf(
        "Sports" to "sports",
        "News" to "news",
        "Movies" to "movies",
        "Series" to "series",
        "Music" to "music",
        "Entertainment" to "entertainment",
        "Documentary" to "documentary",
        "Kids" to "kids",
        "Comedy" to "comedy",
        "General" to "general",
        "Science" to "science",
        "Lifestyle" to "lifestyle",
        "Family" to "family",
        "Culture" to "culture",
        "Animation" to "animation",
        "Cooking" to "cooking",
        "Travel" to "travel",
        "Religious" to "religious",
        "Education" to "education",
        "Business" to "business",
        "Weather" to "weather",
    )

    private val cache = mutableMapOf<String, List<Channel>>()

    private val extinfRe = Regex("""#EXTINF:-?\d+([^,]*),(.*)""")
    private val logoRe = Regex("""tvg-logo="([^"]*)"""")
    private val groupRe = Regex("""group-title="([^"]*)"""")

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
