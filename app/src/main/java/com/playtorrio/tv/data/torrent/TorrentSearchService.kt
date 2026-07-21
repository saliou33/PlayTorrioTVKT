package com.playtorrio.tv.data.torrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

object TorrentSearchService {

    private const val TAG = "TorrentSearch"

    /** Free-text search for the Torrent hub — searches every indexer with the
     *  raw query (no title-relevance filtering), deduped and sorted by seeders. */
    suspend fun searchRaw(query: String): List<TorrentResult> = coroutineScope {
        val q = query.trim()
        if (q.isBlank()) return@coroutineScope emptyList()
        val uindex = async { runCatching { searchUindex(q) }.getOrDefault(emptyList()) }
        val knaben = async { runCatching { searchKnaben(q) }.getOrDefault(emptyList()) }
        val tpb = async { runCatching { searchApibay(q) }.getOrDefault(emptyList()) }
        val nyaa = async { runCatching { searchNyaa(q) }.getOrDefault(emptyList()) }
        val all = uindex.await() + knaben.await() + tpb.await() + nyaa.await()
        deduplicateResults(all).sortedByDescending { it.seeders }
    }

    // Common public trackers appended to magnets built from an info-hash.
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
    )

    private fun magnetFromHash(hash: String, name: String): String {
        val dn = URLEncoder.encode(name, "UTF-8")
        val tr = TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$hash&dn=$dn$tr"
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i <= 1) "${v.toInt()} ${units[i]}" else String.format("%.1f %s", v, units[i])
    }

    /** Nyaa.si — the best anime torrent index. Uses its RSS feed (stable),
     *  which exposes info-hash / seeders / size per item. */
    private suspend fun searchNyaa(query: String): List<TorrentResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TorrentResult>()
        try {
            val url = "https://nyaa.si/?page=rss&q=${URLEncoder.encode(query, "UTF-8")}&c=0_0&f=0"
            val xml = URL(url).openConnection().apply {
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 10_000; readTimeout = 12_000
            }.getInputStream().bufferedReader().use { it.readText() }

            val itemRe = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL)
            val titleRe = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL)
            val hashRe = Pattern.compile("<nyaa:infoHash>(.*?)</nyaa:infoHash>")
            val seedRe = Pattern.compile("<nyaa:seeders>(\\d+)</nyaa:seeders>")
            val leechRe = Pattern.compile("<nyaa:leechers>(\\d+)</nyaa:leechers>")
            val sizeRe = Pattern.compile("<nyaa:size>(.*?)</nyaa:size>")

            val items = itemRe.matcher(xml)
            while (items.find()) {
                val block = items.group(1) ?: continue
                val hash = hashRe.matcher(block).let { if (it.find()) it.group(1) else null } ?: continue
                val name = titleRe.matcher(block).let { if (it.find()) it.group(1)?.trim() else null } ?: continue
                val seeders = seedRe.matcher(block).let { if (it.find()) it.group(1)?.toIntOrNull() else null } ?: 0
                val leechers = leechRe.matcher(block).let { if (it.find()) it.group(1)?.toIntOrNull() else null } ?: 0
                val size = sizeRe.matcher(block).let { if (it.find()) it.group(1) else null } ?: ""
                out.add(
                    TorrentResult(
                        name = name,
                        magnetLink = magnetFromHash(hash, name),
                        size = size,
                        seeders = seeders,
                        leechers = leechers,
                        source = "Nyaa",
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "nyaa search failed: $query", e)
        }
        out
    }

    // Torrent categories for the hub. apibayCat = ThePirateBay category code
    // (0 = all). topCode = parent code for apibay's precompiled top-100 file
    // (reliable). Anime is served best by Nyaa. adult ones are gated by the
    // 18+ content setting.
    enum class TorrentCategory(
        val label: String, val apibayCat: String, val topCode: String,
        val nyaaOnly: Boolean = false, val adult: Boolean = false,
    ) {
        ALL("All", "0", "all"),
        MOVIES("Movies", "201", "200"),
        HD_MOVIES("4K/HD Movies", "207", "200"),
        TV("Series", "205", "200"),
        HD_TV("4K/HD Series", "208", "200"),
        ANIME("Anime", "0", "all", nyaaOnly = true),
        MUSIC("Music", "100", "100"),
        GAMES("Games", "400", "400"),
        SOFTWARE("Software", "300", "300"),
        BOOKS("Books", "601", "600"),
        XXX("XXX", "500", "500", adult = true),
    }

    /** Category-aware search for the hub. */
    suspend fun searchCategory(query: String, cat: TorrentCategory): List<TorrentResult> = coroutineScope {
        val q = query.trim()
        if (q.isBlank()) return@coroutineScope emptyList()
        if (cat.nyaaOnly) {
            return@coroutineScope deduplicateResults(
                runCatching { searchNyaa(q) }.getOrDefault(emptyList())
            ).sortedByDescending { it.seeders }
        }
        val uindex = async { runCatching { searchUindex(q) }.getOrDefault(emptyList()) }
        val knaben = async { runCatching { searchKnaben(q) }.getOrDefault(emptyList()) }
        val tpb = async { runCatching { searchApibay(q, cat.apibayCat) }.getOrDefault(emptyList()) }
        deduplicateResults(uindex.await() + knaben.await() + tpb.await()).sortedByDescending { it.seeders }
    }

    /** Popular torrents for the landing page (per category). Uses apibay's
     *  precompiled top-100 file (reliable) or Nyaa for anime. */
    // Popular lists cached ~10 min per category so revisits are instant.
    private val popularCache = HashMap<String, Pair<Long, List<TorrentResult>>>()
    private const val POPULAR_TTL_MS = 10 * 60_000L

    suspend fun popular(cat: TorrentCategory): List<TorrentResult> = withContext(Dispatchers.IO) {
        synchronized(popularCache) {
            popularCache[cat.name]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < POPULAR_TTL_MS && list.isNotEmpty()) return@withContext list
            }
        }
        val list = if (cat.nyaaOnly) {
            runCatching { searchNyaa("1080p") }.getOrDefault(emptyList())
                .sortedByDescending { it.seeders }.take(60)
        } else {
            val url = "https://apibay.org/precompiled/data_top100_${cat.topCode}.json"
            runCatching { parseApibay(fetch(url)) }.getOrDefault(emptyList())
                .sortedByDescending { it.seeders }.take(60)
        }
        if (list.isNotEmpty()) synchronized(popularCache) {
            popularCache[cat.name] = System.currentTimeMillis() to list
        }
        list
    }

    // ── Poster matching (best-effort) ──────────────────────────────────────
    // Torrent filenames are messy; we strip release tags to a rough title and
    // ask TMDB for a poster. Cached per cleaned title. Returns null when there's
    // no confident match (the row just shows its icon).
    private val posterCache = HashMap<String, String?>()

    private fun cleanTitle(raw: String): String {
        var s = raw.replace('.', ' ').replace('_', ' ')
        val cut = Regex(
            """(?i)\b((19|20)\d{2}|2160p|1080p|720p|480p|x264|x265|h ?264|h ?265|hevc|web[- ]?dl|webrip|bluray|blu[- ]?ray|brrip|bdrip|hdrip|dvdrip|hdtv|s\d{1,2}e\d{1,2}|s\d{1,2}|season|complete|multi|dual)\b"""
        ).find(s)
        if (cut != null && cut.range.first > 0) s = s.substring(0, cut.range.first)
        return s.replace(Regex("""[\[\](){}]"""), " ").replace(Regex("\\s+"), " ").trim()
    }

    suspend fun posterFor(name: String): String? = withContext(Dispatchers.IO) {
        val title = cleanTitle(name)
        if (title.length < 2) return@withContext null
        synchronized(posterCache) { if (posterCache.containsKey(title)) return@withContext posterCache[title] }
        val url = runCatching {
            com.playtorrio.tv.data.api.TmdbClient.api
                .searchMulti(
                    apiKey = com.playtorrio.tv.data.api.TmdbClient.API_KEY,
                    query = title,
                )
                .results.firstOrNull { it.posterUrl != null }?.posterUrl
        }.getOrNull()
        synchronized(posterCache) { posterCache[title] = url }
        url
    }

    private fun fetch(url: String): String =
        URL(url).openConnection().apply {
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connectTimeout = 10_000; readTimeout = 12_000
        }.getInputStream().bufferedReader().use { it.readText() }

    private fun parseApibay(body: String): List<TorrentResult> {
        val out = mutableListOf<TorrentResult>()
        val arr = org.json.JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hash = o.optString("info_hash")
            // apibay returns a single sentinel row when there are no matches.
            if (hash.isBlank() || hash.all { it == '0' }) continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out.add(
                TorrentResult(
                    name = name,
                    magnetLink = magnetFromHash(hash, name),
                    size = humanSize(o.optString("size").toLongOrNull() ?: 0L),
                    seeders = o.optString("seeders").toIntOrNull() ?: 0,
                    leechers = o.optString("leechers").toIntOrNull() ?: 0,
                    source = "ThePirateBay",
                )
            )
        }
        return out
    }

    /** ThePirateBay via the apibay JSON API — reliable (no HTML scraping). */
    private suspend fun searchApibay(query: String, cat: String = "0"): List<TorrentResult> = withContext(Dispatchers.IO) {
        try {
            parseApibay(fetch("https://apibay.org/q.php?q=${URLEncoder.encode(query, "UTF-8")}&cat=$cat"))
        } catch (e: Exception) {
            Log.e(TAG, "apibay search failed: $query", e); emptyList()
        }
    }

    suspend fun search(request: TorrentSearchRequest): List<TorrentResult> = coroutineScope {
        val allResults = mutableListOf<TorrentResult>()
        // Normalize title for search queries (strip special chars that break indexer search)
        val searchTitle = normalizeTitle(request.title)

        if (request.isMovie) {
            // Movies: 2 searches (uindex + knaben)
            val query = "$searchTitle ${request.year ?: ""}".trim()
            val uindex = async { searchUindex(query) }
            val knaben = async { searchKnaben(query) }
            allResults.addAll(uindex.await())
            allResults.addAll(knaben.await())
        } else {
            // TV: 4 searches (episode + season pack on both sources)
            val sNum = String.format("S%02d", request.seasonNumber ?: 1)
            val eNum = String.format("E%02d", request.episodeNumber ?: 1)
            val episodeQuery = "$searchTitle ${sNum}${eNum}"
            val seasonQuery = "$searchTitle ${sNum}"

            val uEpisode = async { searchUindex(episodeQuery) }
            val uSeason = async { searchUindex(seasonQuery) }
            val kEpisode = async { searchKnaben(episodeQuery) }
            val kSeason = async { searchKnaben(seasonQuery) }

            allResults.addAll(uEpisode.await())
            allResults.addAll(uSeason.await())
            allResults.addAll(kEpisode.await())
            allResults.addAll(kSeason.await())
        }

        // Filter and deduplicate
        Log.d(TAG, "Raw results: ${allResults.size}, title='${request.title}', normalized='$searchTitle'")
        val filtered = filterResults(allResults, request)
        Log.d(TAG, "After filter: ${filtered.size}")
        deduplicateResults(filtered)
            .sortedByDescending { it.seeders }
    }

    // ── UINDEX SCRAPER ──

    private suspend fun searchUindex(query: String): List<TorrentResult> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://uindex.org/search.php?search=$encoded&c=0&sort=seeders&order=DESC"
            val html = fetchHtml(url)
            parseUindex(html)
        } catch (e: Exception) {
            Log.e(TAG, "uindex search failed: $query", e)
            emptyList()
        }
    }

    private fun parseUindex(html: String): List<TorrentResult> {
        val results = mutableListOf<TorrentResult>()

        // Match each <tr> in the results table
        val rowPattern = Pattern.compile(
            "<tr>\\s*<td class=\"sr-col-cat\">(.*?)</td>\\s*<td class=\"sr-col-name\">(.*?)</td>\\s*<td class=\"sr-col-size\">(.*?)</td>\\s*<td class=\"sr-col-uploaded\"[^>]*>(.*?)</td>\\s*<td class=\"sr-col-seeders\">\\s*<span class=\"sr-seed\">(\\d+)</span>\\s*</td>\\s*<td class=\"sr-col-leechers\">\\s*<span class=\"sr-leech\">(\\d+)</span>\\s*</td>\\s*</tr>",
            Pattern.DOTALL
        )
        val matcher = rowPattern.matcher(html)

        while (matcher.find()) {
            val nameCell = matcher.group(2) ?: continue
            val size = matcher.group(3)?.trim() ?: ""
            val seeders = matcher.group(5)?.toIntOrNull() ?: 0
            val leechers = matcher.group(6)?.toIntOrNull() ?: 0

            // Extract magnet link
            val magnetMatch = Pattern.compile("href=\"(magnet:\\?[^\"]+)\"").matcher(nameCell)
            val magnet = if (magnetMatch.find()) {
                magnetMatch.group(1)?.replace("&amp;", "&") ?: continue
            } else continue

            // Extract torrent name
            val nameMatch = Pattern.compile("class=\"sr-torrent-link\"[^>]*title=\"([^\"]+)\"").matcher(nameCell)
            val name = if (nameMatch.find()) {
                nameMatch.group(1) ?: continue
            } else continue

            results.add(TorrentResult(
                name = name,
                magnetLink = magnet,
                size = size,
                seeders = seeders,
                leechers = leechers,
                source = "uindex"
            ))
        }

        return results
    }

    // ── KNABEN SCRAPER ──

    private suspend fun searchKnaben(query: String): List<TorrentResult> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val url = "https://knaben.org/search/$encoded/0/1/seeders"
            val html = fetchHtml(url)
            parseKnaben(html)
        } catch (e: Exception) {
            Log.e(TAG, "knaben search failed: $query", e)
            emptyList()
        }
    }

    private fun parseKnaben(html: String): List<TorrentResult> {
        val results = mutableListOf<TorrentResult>()

        // Split into rows by <tr
        val rows = html.split("<tr ").drop(1) // skip header

        for (row in rows) {
            try {
                // Extract magnet link
                val magnetMatch = Pattern.compile("href=\"(magnet:\\?[^\"]+)\"").matcher(row)
                val magnet = if (magnetMatch.find()) {
                    magnetMatch.group(1)?.replace("&amp;", "&") ?: continue
                } else continue

                // Extract name from the first <a with title= and magnet href
                val nameMatch = Pattern.compile("<a\\s+title=\"([^\"]+)\"\\s+href=\"magnet:").matcher(row)
                val name = if (nameMatch.find()) {
                    nameMatch.group(1) ?: continue
                } else continue

                // Extract TDs after the name cell: size, date, seeders, leechers
                // Pattern: <td ...>SIZE</td> <td ...>DATE</td> <td>SEEDERS</td> <td>LEECHERS</td>
                val tdPattern = Pattern.compile("<td[^>]*>([^<]*)</td>")
                val tdMatcher = tdPattern.matcher(row)
                val tdValues = mutableListOf<String>()
                while (tdMatcher.find()) {
                    tdValues.add(tdMatcher.group(1)?.trim() ?: "")
                }

                // Find size (contains GB, MB, TB)
                val sizeValue = tdValues.firstOrNull {
                    it.contains("GB", true) || it.contains("MB", true) || it.contains("TB", true)
                } ?: ""

                // Find seeders/leechers - they're plain numbers in td
                val numbers = tdValues.filter { it.matches(Regex("^\\d+$")) }
                val seeders = numbers.getOrNull(0)?.toIntOrNull() ?: 0
                val leechers = numbers.getOrNull(1)?.toIntOrNull() ?: 0

                results.add(TorrentResult(
                    name = name,
                    magnetLink = magnet,
                    size = sizeValue,
                    seeders = seeders,
                    leechers = leechers,
                    source = "knaben"
                ))
            } catch (_: Exception) {
                continue
            }
        }

        return results
    }

    // ── SMART FILTERING ──

    private fun filterResults(
        results: List<TorrentResult>,
        request: TorrentSearchRequest
    ): List<TorrentResult> {
        val normalizedTitle = normalizeTitle(request.title).lowercase()

        return results.filter { result ->
            val normalizedName = normalizeTitle(result.name).lowercase()

            // Core check: the full title must appear as a contiguous phrase
            val titleIndex = normalizedName.indexOf(normalizedTitle)
            if (titleIndex < 0) return@filter false

            if (request.isMovie) {
                // For movies: check year is present
                val yearMatch = request.year == null || normalizedName.contains(request.year!!)
                // Filter out TV show patterns (S01E01, etc.)
                val hasTvPattern = Regex("s\\d{2}e\\d{2}", RegexOption.IGNORE_CASE).containsMatchIn(normalizedName)
                yearMatch && !hasTvPattern
            } else {
                val sNum = String.format("S%02d", request.seasonNumber ?: 1)
                val eNum = String.format("E%02d", request.episodeNumber ?: 1)
                val seasonEpisode = "$sNum$eNum"

                // Title must appear BEFORE the season marker
                // (prevents matching episode descriptions that mention the show title)
                val seasonIdx = normalizedName.indexOf(sNum.lowercase())
                if (seasonIdx >= 0 && titleIndex > seasonIdx) return@filter false

                // Must be the correct show (not a spinoff)
                if (!isCorrectShow(normalizedName, normalizedTitle)) return@filter false

                // Check if it's the exact episode, an episode range containing it, or a season pack
                val hasExactEpisode = normalizedName.contains(seasonEpisode, ignoreCase = true)
                val hasEpisodeInRange = isEpisodeInRange(normalizedName, request.seasonNumber ?: 1, request.episodeNumber ?: 1)
                val isSeasonPack = isSeasonPack(normalizedName, sNum, request)

                hasExactEpisode || hasEpisodeInRange || isSeasonPack
            }
        }.map { result ->
            // Mark season packs (anything without the exact S02E03 match)
            if (!request.isMovie) {
                val sNum = String.format("S%02d", request.seasonNumber ?: 1)
                val eNum = String.format("E%02d", request.episodeNumber ?: 1)
                val hasExact = result.name.contains("$sNum$eNum", ignoreCase = true)
                val hasRange = isEpisodeInRange(result.name, request.seasonNumber ?: 1, request.episodeNumber ?: 1)
                val isPack = !hasExact && !hasRange
                result.copy(isSeasonPack = isPack)
            } else result
        }
    }

    private fun isCorrectShow(resultName: String, searchTitle: String): Boolean {
        // Detect spinoffs by checking if there are extra title words between
        // the matching show name and the season marker.
        // e.g. "the walking dead daryl dixon s01" has "daryl dixon" extra -> spinoff
        // vs "the walking dead s01" -> correct

        val titleWords = searchTitle.split(" ").filter { it.length > 1 }

        // Find where the title ends in the result name
        var searchStart = 0
        for (word in titleWords) {
            val idx = resultName.indexOf(word, searchStart, ignoreCase = true)
            if (idx < 0) return false
            searchStart = idx + word.length
        }

        // Get the text between end of title match and the season marker
        val seasonMatch = Regex("s\\d{2}", RegexOption.IGNORE_CASE).find(resultName, searchStart)
        if (seasonMatch != null) {
            val gap = resultName.substring(searchStart, seasonMatch.range.first).trim()
            // Gap should be empty or just dots/dashes/spaces
            val gapWords = gap.replace(Regex("[.\\-_]"), " ").trim().split(" ")
                .filter { it.isNotBlank() && it.length > 1 }
            // If there are significant words in the gap, it's probably a spinoff
            if (gapWords.isNotEmpty()) {
                // Allow common words like "the", "and", "of" etc
                val stopWords = setOf("the", "and", "of", "a", "an", "in", "on", "at", "to")
                val significantGapWords = gapWords.filter { it.lowercase() !in stopWords }
                if (significantGapWords.isNotEmpty()) return false
            }
        }

        return true
    }

    private fun isSeasonPack(name: String, seasonCode: String, request: TorrentSearchRequest): Boolean {
        val hasSeasonCode = name.contains(seasonCode, ignoreCase = true)
        if (!hasSeasonCode) return false

        // It's a season pack if it has the season code but no specific episode
        val hasSpecificEpisode = Regex("${seasonCode}E\\d{2}", RegexOption.IGNORE_CASE).containsMatchIn(name)
        if (hasSpecificEpisode) return false

        // Or if it says "COMPLETE" or "Season"
        val hasPackIndicator = name.contains("COMPLETE", ignoreCase = true) ||
                name.contains("Season", ignoreCase = true) ||
                name.contains("FULL", ignoreCase = true)

        // If it just has S01 without any episode number, it's a season pack
        return !hasSpecificEpisode
    }

    /** Detect multi-episode ranges like S02E01-03, S02E01-E03, S02e01-03 */
    private fun isEpisodeInRange(name: String, season: Int, episode: Int): Boolean {
        val sCode = String.format("S%02d", season)
        // Match patterns: S02E01-03, S02E01-E03, S02e01-03, S02e01-e03
        val rangeRegex = Regex(
            "${sCode}E(\\d{2})\\s*-\\s*E?(\\d{2})",
            RegexOption.IGNORE_CASE
        )
        rangeRegex.findAll(name).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (episode in start..end) return true
        }
        return false
    }

    // ── DEDUPLICATION ──

    private fun deduplicateResults(results: List<TorrentResult>): List<TorrentResult> {
        // Deduplicate by magnet hash (btih)
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val hash = extractInfoHash(result.magnetLink)?.lowercase()
            if (hash != null && hash in seen) false
            else {
                if (hash != null) seen.add(hash)
                true
            }
        }
    }

    private fun extractInfoHash(magnet: String): String? {
        val match = Regex("btih:([a-fA-F0-9]+)").find(magnet)
        return match?.groupValues?.get(1)
    }

    // ── UTILS ──

    private fun normalizeTitle(title: String): String {
        return title
            .replace("'", "")   // Marvel's → Marvels
            .replace("\u2019", "")  // curly apostrophe
            .replace(":", " ")  // Daredevil: Born Again → Daredevil  Born Again
            .replace(",", "")
            .replace("&", "and")
            .replace(Regex("[._\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun fetchHtml(url: String): String {
        val connection = URL(url).openConnection()
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        return connection.getInputStream().bufferedReader().use { it.readText() }
    }
}
