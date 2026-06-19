package com.playtorrio.tv.data.anime.extractors

import android.util.Log
import com.playtorrio.tv.data.anime.AnimeEmbed
import com.playtorrio.tv.data.anime.AnimeStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object HentainiExtractor {
    private const val TAG = "HentainiExtractor"

    private const val SITE = "https://hentaini.com"
    private const val API = "https://admin.hentaini.com/api"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val STOPWORDS = setOf(
        "a", "an", "the", "of", "and", "or", "to", "in", "on", "at",
        "for", "with", "by", "from", "is", "it",
        "no", "wa", "ga", "ni", "o", "wo", "de", "mo", "ka", "ya",
        "na", "e", "he", "te", "ne",
        "animation", "anime", "motion", "ova", "ona", "tv", "special",
        "version", "edition", "dubbed", "subbed", "sub", "dub",
        "uncensored", "censored", "episode", "ep", "season",
        "side", "part", "arc", "chapter", "vol", "volume"
    )

    private class HSeries(val id: Int, val title: String, val titleEnglish: String, val url: String)

    suspend fun extract(embed: AnimeEmbed): AnimeStreamResult? {
        val m = Regex("^hentaini://discover/(\\d+)\\?t=(.+)$").find(embed.url) ?: return null
        val ep = m.groupValues[1].toInt()
        val titles = m.groupValues[2].split(",")
            .mapNotNull { java.net.URLDecoder.decode(it, "UTF-8").ifBlank { null } }
        return extract(titles, ep)
    }

    private suspend fun extract(titleCandidates: List<String>, episode: Int): AnimeStreamResult? = withContext(Dispatchers.IO) {
        try {
            val series = findSeries(titleCandidates) ?: return@withContext null
            val url = "$API/series?filters%5Bid%5D=${series.id}&populate=episodes"
            val body = getHtml(url, true, "$SITE/") ?: return@withContext null

            val j = JSONObject(body)
            val data = j.optJSONArray("data") ?: return@withContext null
            if (data.length() == 0) return@withContext null
            val episodes = data.optJSONObject(0)?.optJSONArray("episodes") ?: return@withContext null

            var targetEp: JSONObject? = null
            for (i in 0 until episodes.length()) {
                val e = episodes.optJSONObject(i) ?: continue
                if (e.optInt("episode_number", -1) == episode) {
                    targetEp = e
                    break
                }
            }
            if (targetEp == null) return@withContext null

            val players = targetEp.optString("players", "")
            if (players.isEmpty()) return@withContext null

            val stream = pickPlayer(players) ?: return@withContext null

            AnimeStreamResult(
                url = stream,
                referer = "$SITE/",
                origin = SITE
            )
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            null
        }
    }

    private suspend fun getHtml(url: String, json: Boolean = false, referer: String? = null): String? {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", if (json) "application/json" else "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (referer != null) setRequestProperty("Referer", referer)
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode != 200) return null
            return InputStreamReader(conn.inputStream, Charsets.UTF_8).readText()
        } catch (e: Exception) {
            Log.e(TAG, "getHtml failed for $url", e)
            return null
        }
    }

    private fun tokens(s: String): Set<String> {
        val lower = s.lowercase()
        val cleaned = lower.replace(Regex("[^a-z0-9]+"), " ")
        return cleaned.split(Regex("\\s+"))
            .filter { it.length > 1 && !STOPWORDS.contains(it) }
            .toSet()
    }

    private fun titleVariants(t: String): List<String> {
        val out = mutableSetOf(t.trim())
        val pats = listOf(
            Regex("[:\u2013\u2014]"),
            Regex("\\s+~"),
            Regex("\\s+-\\s+"),
            Regex("\\s*\\("),
            Regex("\\s*/"),
            Regex("\\s+(side|part|arc)\\s+", RegexOption.IGNORE_CASE)
        )
        for (pat in pats) {
            val m = pat.find(t)
            if (m != null && m.range.first > 0) {
                out.add(t.substring(0, m.range.first).trim())
            }
        }
        val decoStripped = t.replace(
            Regex("\\s+(the\\s+)?(animation|motion\\s+anime|anime|ova|ona|special)\\s*$", RegexOption.IGNORE_CASE),
            ""
        )
        if (decoStripped != t) out.add(decoStripped.trim())

        val words = t.trim().split(Regex("\\s+"))
        if (words.size > 2) out.add(words.take(2).joinToString(" "))
        if (words.size > 3) out.add(words.take(3).joinToString(" "))

        return out.filter { it.isNotEmpty() }
    }

    private fun parseSeriesList(body: String): List<HSeries> {
        try {
            val j = JSONObject(body)
            val data = j.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<HSeries>()
            for (i in 0 until data.length()) {
                val e = data.optJSONObject(i) ?: continue
                val id = e.optInt("id", -1)
                val title = e.optString("title", "")
                val titleEn = e.optString("title_english", "")
                val url = e.optString("url", "")
                if (id != -1 && url.isNotEmpty()) {
                    out.add(HSeries(id, title, titleEn, url))
                }
            }
            return out
        } catch (e: Exception) {
            Log.e(TAG, "parseSeriesList failed", e)
            return emptyList()
        }
    }

    private fun scoreSeries(s: HSeries, queries: List<Set<String>>): Double {
        val candidates = mutableListOf<Set<String>>()
        if (s.title.isNotEmpty()) candidates.add(tokens(s.title))
        if (s.titleEnglish.isNotEmpty()) candidates.add(tokens(s.titleEnglish))
        candidates.add(tokens(s.url.replace('-', ' ')))

        var best = 0.0
        for (r in candidates) {
            if (r.isEmpty()) continue
            for (q in queries) {
                if (q.isEmpty()) continue
                val inter = r.intersect(q).size
                if (inter == 0) continue
                val union = r.size + q.size - inter
                val j = inter.toDouble() / union
                if (j > best) best = j
            }
        }
        return best
    }

    private suspend fun findSeries(titles: List<String>): HSeries? {
        val allVariants = mutableSetOf<String>()
        for (t in titles) {
            for (v in titleVariants(t)) {
                if (v.isNotEmpty()) allVariants.add(v)
            }
        }
        if (allVariants.isEmpty()) return null

        val orderedVariants = allVariants.toList().sortedBy { it.length }
        val qSets = orderedVariants.map { tokens(it) }.filter { it.isNotEmpty() }
        if (qSets.isEmpty()) return null

        val tried = mutableSetOf<String>()
        val allHits = mutableListOf<HSeries>()

        for (q in orderedVariants.take(4)) {
            val key = q.lowercase()
            if (!tried.add(key)) continue
            
            val url1 = "$API/series?filters%5Btitle%5D%5B%24containsi%5D=${URLEncoder.encode(q, "UTF-8")}&pagination%5Blimit%5D=20"
            val body1 = getHtml(url1, true, "$SITE/") ?: continue
            val hits1 = parseSeriesList(body1)

            val url2 = "$API/series?filters%5Btitle_english%5D%5B%24containsi%5D=${URLEncoder.encode(q, "UTF-8")}&pagination%5Blimit%5D=20"
            val body2 = getHtml(url2, true, "$SITE/")
            val hits2 = if (body2 != null) parseSeriesList(body2) else emptyList()

            for (h in hits1 + hits2) {
                if (allHits.none { it.id == h.id }) {
                    allHits.add(h)
                }
            }

            if (allHits.isNotEmpty()) {
                val s = scoreSeries(allHits.first(), qSets)
                if (s >= 0.99) break
            }
        }
        if (allHits.isEmpty()) return null

        var best: HSeries? = null
        var bestScore = -1.0

        for (h in allHits) {
            val s = scoreSeries(h, qSets)
            if (s > bestScore) {
                bestScore = s
                best = h
            }
        }

        if (bestScore < 0.45) return null
        return best
    }

    private fun pickPlayer(playersJson: String): String? {
        try {
            val list = JSONArray(playersJson)
            var hls: String? = null
            var mp4: String? = null

            for (i in 0 until list.length()) {
                val p = list.optJSONObject(i) ?: continue
                val name = p.optString("name", "").uppercase()
                val url = p.optString("url", "")
                if (url.isEmpty()) continue

                if (name == "HLS" && url.endsWith(".m3u8")) {
                    if (hls == null) hls = url
                } else if (Regex("\\.mp4($|\\?)").containsMatchIn(url) && !url.contains("/embed")) {
                    if (mp4 == null) mp4 = url
                }
            }
            return hls ?: mp4
        } catch (e: Exception) {
            Log.e(TAG, "pickPlayer failed", e)
            return null
        }
    }
}
