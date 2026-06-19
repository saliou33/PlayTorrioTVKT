package com.playtorrio.tv.data.anime.extractors

import android.util.Log
import com.playtorrio.tv.data.anime.AnimeEmbed
import com.playtorrio.tv.data.anime.AnimeStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WatchHentaiExtractor {
    private const val TAG = "WatchHentaiExtractor"

    private const val ORIGIN = "https://watchhentai.net"
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

    private class SearchHit(val url: String, val title: String)

    suspend fun extract(embed: AnimeEmbed): AnimeStreamResult? {
        val m = Regex("^watchhentai://discover/(\\d+)\\?t=(.+)$").find(embed.url) ?: return null
        val ep = m.groupValues[1].toInt()
        val titles = m.groupValues[2].split(",")
            .mapNotNull { java.net.URLDecoder.decode(it, "UTF-8").ifBlank { null } }
        return extract(titles, ep)
    }

    private suspend fun extract(titleCandidates: List<String>, episode: Int): AnimeStreamResult? = withContext(Dispatchers.IO) {
        try {
            val seriesUrl = findSeries(titleCandidates) ?: return@withContext null
            val seriesHtml = getHtml(seriesUrl) ?: return@withContext null
            
            val videoUrl = pickEpisode(seriesHtml, episode) ?: return@withContext null
            val videoHtml = getHtml(videoUrl, ORIGIN) ?: return@withContext null
            
            val jwUrl = extractStreamUrl(videoHtml) ?: return@withContext null
            val jwHtml = getHtml(jwUrl, videoUrl) ?: return@withContext null
            
            val stream = pickBestSource(jwHtml) ?: return@withContext null

            AnimeStreamResult(
                url = stream,
                referer = "$ORIGIN/",
                origin = ORIGIN
            )
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            null
        }
    }

    private suspend fun getHtml(url: String, referer: String? = null): String? {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Cache-Control", "no-cache")
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

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&#8217;", "\u2019")
        .replace("&#8220;", "\u201C")
        .replace("&#8221;", "\u201D")
        .replace("&#8211;", "\u2013")
        .replace("&#8212;", "\u2014")
        .replace("&#8230;", "\u2026")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

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

    private fun parseHits(html: String): List<SearchHit> {
        val start = html.indexOf("csearch")
        if (start < 0) return emptyList()
        val end = html.indexOf("class=\"sidebar", start)
        if (end < 0 || end <= start) return emptyList()
        val region = html.substring(start, end)
        
        val rx = Regex(
            """<div class="result-item"><article>.*?<div class="title">\s*<a href="([^"]+)">([^<]+)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val hits = mutableListOf<SearchHit>()
        for (m in rx.findAll(region)) {
            hits.add(SearchHit(m.groupValues[1], decodeEntities(m.groupValues[2].trim())))
        }
        return hits
    }

    private fun scoreHit(hit: SearchHit, queries: List<Set<String>>): Double {
        val r = tokens(hit.title)
        if (r.isEmpty()) return 0.0
        var best = 0.0
        for (q in queries) {
            if (q.isEmpty()) continue
            val inter = r.intersect(q).size
            if (inter == 0) continue
            val union = r.size + q.size - inter
            val j = inter.toDouble() / union
            if (j > best) best = j
        }
        return best
    }

    private suspend fun findSeries(titles: List<String>): String? {
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

        val triedQueries = mutableSetOf<String>()
        val allHits = mutableListOf<SearchHit>()

        for (q in orderedVariants.take(4)) {
            val key = q.lowercase()
            if (!triedQueries.add(key)) continue
            val url = "$ORIGIN/?s=${URLEncoder.encode(q, "UTF-8")}"
            val html = getHtml(url) ?: continue
            val hits = parseHits(html)
            for (h in hits) {
                if (allHits.none { it.url == h.url }) {
                    allHits.add(h)
                }
            }
            if (hits.isNotEmpty()) {
                val s = scoreHit(hits.first(), qSets)
                if (s >= 0.99) break
            }
        }
        if (allHits.isEmpty()) return null

        var best: SearchHit? = null
        var bestScore = -1.0
        var bestLen = Int.MAX_VALUE

        for (h in allHits) {
            val s = scoreHit(h, qSets)
            val len = tokens(h.title).size
            if (s > bestScore || (s == bestScore && len < bestLen)) {
                bestScore = s
                best = h
                bestLen = len
            }
        }

        if (bestScore < 0.50) return null
        return best?.url
    }

    private fun pickEpisode(seriesHtml: String, ep: Int): String? {
        val all = Regex("""/videos/([a-z0-9\-]+-episode-(\d+)[a-z0-9\-]*)/?""", RegexOption.IGNORE_CASE)
            .findAll(seriesHtml)
            .map { "/videos/${it.groupValues[1]}/" }
            .toSet()
            .toList()

        val epStr = ep.toString()
        val matching = all.filter { u ->
            val m = Regex("-episode-(\\d+)").find(u)
            m != null && m.groupValues[1] == epStr
        }.toMutableList()

        if (matching.isEmpty()) return null

        matching.sortBy { u ->
            var s = 0
            if (u.contains("dubbed")) s -= 100
            if (u.contains("uncensored")) s -= 10
            s
        }
        return "$ORIGIN${matching.first()}"
    }

    private fun extractStreamUrl(videoHtml: String): String? {
        val m = Regex("""(?:data-litespeed-src|src)\s*=\s*['"](https?://watchhentai\.net/jwplayer/\?source=[^'"]+)""", RegexOption.IGNORE_CASE)
            .find(videoHtml)
        if (m == null) return null
        return decodeEntities(m.groupValues[1])
    }

    private fun pickBestSource(jwHtml: String): String? {
        val all = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4)["']""", RegexOption.IGNORE_CASE)
            .findAll(jwHtml)
            .map { it.groupValues[1] }
            .toList()
        if (all.isEmpty()) return null

        val qualified = all.filter { Regex("_(\\d+)p\\.mp4$").containsMatchIn(it) }.toMutableList()
        if (qualified.isNotEmpty()) {
            qualified.sortByDescending { u ->
                Regex("_(\\d+)p\\.mp4$").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            return qualified.first()
        }
        return all.first()
    }
}
