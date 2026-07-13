package com.playtorrio.tv.data.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Scraper + fuzzy matcher for the 111477.xyz file index — ported from the
 * PlayTorrio mobile app (its highest-priority source).
 *
 * `/movies/` and `/tvs/` are flat HTML directory indexes (~3-8 MB each). We
 * download once, cache to disk for 24 h, then resolve a TMDB title → exact
 * file URL via a normalized-title + year match. The result is a DIRECT
 * .mkv/.mp4 URL that ExoPlayer can play without any WebView sniffing.
 *
 * On HTTP 429 / 5xx / Cloudflare 1015 we wait 7.2 s and retry.
 */
object Site111477Service {

    private const val TAG = "Site111477"
    private const val BASE_URL = "https://a.111477.xyz"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val RATE_LIMIT_WAIT_MS = 7_200L
    private const val MAX_RATE_LIMIT_RETRIES = 6
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Referer to use when streaming a matched file through ExoPlayer. */
    const val REFERER = "$BASE_URL/"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Match(val fileUrl: String, val fileName: String, val sizeBytes: Long)

    private data class Entry(
        val rawName: String,
        val url: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val normalizedTitle: String,
        val year: String?,
    )

    // Lazy in-memory parsed listings.
    @Volatile private var movies: List<Entry>? = null
    @Volatile private var tvs: List<Entry>? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Pre-fetch and cache the movie/tv indexes in the background so the first
     *  "Play Now" resolves instantly instead of downloading a multi-MB index. */
    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        runCatching { ensureMovies(context) }
        runCatching { ensureTvs(context) }
        Unit
    }

    suspend fun findMovie(context: Context, title: String, year: String?): Match? =
        findMovieSources(context, title, year).firstOrNull()

    suspend fun findEpisode(
        context: Context, showTitle: String, season: Int, episode: Int
    ): Match? = findEpisodeSources(context, showTitle, season, episode).firstOrNull()

    suspend fun findMovieSources(
        context: Context, title: String, year: String?
    ): List<Match> = withContext(Dispatchers.IO) {
        val list = ensureMovies(context)
        val wanted = normalize(title)
        Log.i(TAG, "findMovieSources(\"$title\", year=$year) — ${list.size} entries")

        var hit: Entry? = null
        if (!year.isNullOrEmpty()) {
            hit = list.firstOrNull { it.normalizedTitle == wanted && it.year == year }
        }
        if (hit == null) {
            hit = list.firstOrNull {
                if (it.normalizedTitle != wanted) return@firstOrNull false
                if (year.isNullOrEmpty()) return@firstOrNull true
                val w = year.toIntOrNull()
                val y = it.year?.toIntOrNull()
                w != null && y != null && kotlin.math.abs(w - y) <= 1
            }
        }
        if (hit == null) hit = list.firstOrNull { it.normalizedTitle == wanted }

        if (hit == null) {
            Log.i(TAG, "no movie folder match for \"$title\" ($year)")
            return@withContext emptyList()
        }
        Log.i(TAG, "movie match: ${hit.rawName} → ${hit.url}")
        listFilesInFolder(hit.url)
    }

    suspend fun findEpisodeSources(
        context: Context, showTitle: String, season: Int, episode: Int
    ): List<Match> = withContext(Dispatchers.IO) {
        val list = ensureTvs(context)
        val wanted = normalize(showTitle)

        var folders = list.filter { it.normalizedTitle == wanted }
        if (folders.isEmpty()) folders = list.filter { it.normalizedTitle.startsWith(wanted) }
        if (folders.isEmpty()) {
            Log.i(TAG, "no tv folder match for \"$showTitle\"")
            return@withContext emptyList()
        }

        val out = mutableListOf<Match>()
        val epTag = episodeTag(season, episode).lowercase()
        for (folder in folders) {
            val seasonUrl = absolute("${folder.url}Season $season/")
            val files = try {
                parseEntries(fetchHtml(seasonUrl))
            } catch (e: Exception) {
                Log.w(TAG, "season fetch failed for $seasonUrl: ${e.message}")
                continue
            }
            for (f in files) {
                if (f.isDir) continue
                if (!f.rawName.lowercase().contains(epTag)) continue
                out.add(Match(absolute(f.url), f.rawName, f.sizeBytes))
            }
            if (out.isNotEmpty()) {
                Log.i(TAG, "tv match: ${folder.rawName} S${season}E$episode → ${out.size} file(s)")
                break
            }
        }
        sortByQuality(out)
        out
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private suspend fun ensureMovies(context: Context): List<Entry> {
        movies?.let { return it }
        return loadOrFetch(context, "movies").also { movies = it }
    }

    private suspend fun ensureTvs(context: Context): List<Entry> {
        tvs?.let { return it }
        return loadOrFetch(context, "tvs").also { tvs = it }
    }

    private suspend fun loadOrFetch(context: Context, kind: String): List<Entry> {
        val dir = File(context.cacheDir, "site111477_index").apply { mkdirs() }
        val cacheFile = File(dir, "$kind.html")
        val fresh = cacheFile.exists() &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_TTL_MS
        val html = if (fresh) {
            cacheFile.readText()
        } else {
            fetchHtml("$BASE_URL/$kind/").also {
                runCatching { cacheFile.writeText(it) }
            }
        }
        return parseEntries(html)
    }

    private fun listFilesInFolder(folderRelUrl: String): List<Match> {
        val entries = parseEntries(fetchHtml(absolute(folderRelUrl)))
        val out = entries.filter { !it.isDir }
            .map { Match(absolute(it.url), it.rawName, it.sizeBytes) }
            .toMutableList()
        sortByQuality(out)
        return out
    }

    private fun sortByQuality(list: MutableList<Match>) {
        list.sortWith(compareByDescending<Match> { qualityScore(it.fileName) }
            .thenByDescending { it.sizeBytes })
    }

    // Blocking OkHttp fetch with rate-limit retry. Callers are on Dispatchers.IO.
    private fun fetchHtml(url: String): String {
        var attempt = 0
        while (true) {
            attempt++
            Log.i(TAG, "GET $url (attempt $attempt)")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .build()
            val (code, body) = http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            val limited = code == 429 || code in 500..599 ||
                (body.length < 65536 && isCloudflare1015(body))
            if (!limited && code in 200..399) {
                Log.i(TAG, "OK $code (${body.length} bytes)")
                return body
            }
            if (attempt > MAX_RATE_LIMIT_RETRIES) {
                throw java.io.IOException("111477 fetch failed ($code) after $attempt tries: $url")
            }
            Log.w(TAG, "rate-limited (HTTP $code) — waiting 7.2s")
            Thread.sleep(RATE_LIMIT_WAIT_MS)
        }
    }

    private fun isCloudflare1015(text: String): Boolean {
        if (text.isEmpty()) return false
        return Regex("""\b(?:error\s*(?:code:?\s*)?1015)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("you are being rate limited", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    // ── Parsing ───────────────────────────────────────────────────────────────
    // Rows look like: <tr data-entry="true" data-name="…" data-url="…">…
    //                 …<td class="size" data-sort="12345"…

    private val rowRe = Regex(
        """<tr[^>]*data-entry="true"[^>]*data-name="([^"]*)"[^>]*data-url="([^"]*)"""",
        RegexOption.IGNORE_CASE)
    private val sizeRe = Regex("""<td class="size" data-sort="(-?\d+)"""", RegexOption.IGNORE_CASE)

    private fun parseEntries(html: String): List<Entry> {
        val out = mutableListOf<Entry>()
        for (m in rowRe.findAll(html)) {
            val name = decodeHtml(m.groupValues[1])
            val url = decodeHtml(m.groupValues[2])
            val tailEnd = (m.range.last + 800).coerceAtMost(html.length)
            val sz = sizeRe.find(html.substring(m.range.last, tailEnd))
            val size = sz?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val isDir = url.endsWith("/")
            out.add(
                Entry(
                    rawName = name,
                    url = url,
                    isDir = isDir,
                    sizeBytes = size,
                    normalizedTitle = normalize(stripYearAndExt(name)),
                    year = extractYear(name),
                )
            )
        }
        return out
    }

    private fun decodeHtml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&#x2F;", "/")

    // ── Normalization (deterministic fuzzy match core) ──────────────────────────

    private val diacritics = mapOf(
        'á' to 'a','à' to 'a','ä' to 'a','â' to 'a','ã' to 'a','å' to 'a','ā' to 'a',
        'é' to 'e','è' to 'e','ë' to 'e','ê' to 'e','ē' to 'e','ę' to 'e',
        'í' to 'i','ì' to 'i','ï' to 'i','î' to 'i','ī' to 'i',
        'ó' to 'o','ò' to 'o','ö' to 'o','ô' to 'o','õ' to 'o','ø' to 'o','ō' to 'o',
        'ú' to 'u','ù' to 'u','ü' to 'u','û' to 'u','ū' to 'u',
        'ý' to 'y','ÿ' to 'y','ñ' to 'n','ç' to 'c',
    )

    private fun normalize(input: String): String {
        val sb = StringBuilder()
        for (ch in input.lowercase()) sb.append(diacritics[ch] ?: ch)
        var s = sb.toString()
        s = s.replace("&", " and ")
        s = s.replace(Regex("['‘’ʼ‛`]"), "")
        s = s.replace(Regex("[^a-z0-9]+"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val yearRe = Regex("""\((\d{4})\)\s*$""")

    private fun extractYear(name: String): String? = yearRe.find(name.trim())?.groupValues?.get(1)

    private fun stripYearAndExt(name: String): String {
        var s = name.trim().replaceFirst(yearRe, "").trim()
        s = s.replace(Regex("""\.(mkv|mp4|avi|m4v|mov|webm)$""", RegexOption.IGNORE_CASE), "")
        return s
    }

    private fun episodeTag(season: Int, episode: Int): String =
        "S%02dE%02d".format(season, episode)

    private fun qualityScore(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("2160p") || n.contains("4k") -> 4
            n.contains("1080p") -> 3
            n.contains("720p") -> 2
            n.contains("480p") -> 1
            else -> 0
        }
    }

    private fun absolute(maybeRelative: String): String =
        if (maybeRelative.startsWith("http")) maybeRelative else "$BASE_URL$maybeRelative"
}
