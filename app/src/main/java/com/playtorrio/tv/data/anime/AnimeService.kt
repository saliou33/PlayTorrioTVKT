package com.playtorrio.tv.data.anime

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.playtorrio.tv.data.anime.extractors.AllAnimeExtractor
import com.playtorrio.tv.data.anime.extractors.MiruroExtractor
import com.playtorrio.tv.data.anime.extractors.WatchHentaiExtractor
import com.playtorrio.tv.data.anime.extractors.HentainiExtractor

/**
 * Anime backend — AniList GraphQL for metadata.
 * Sources (in order): megaplay.buzz/vidwish.live (via Anikoto embed IDs),
 * Miruro (miruro.tv secure-pipe), AllAnime (allmanga.to).
 *
 * Ported from Flutter AnimeService + extractors.
 */
object AnimeService {
    private const val TAG = "AnimeService"
    private const val GQL = "https://graphql.anilist.co"
    private const val EMBED_REFERER = "https://www.enma.lol/"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // ── AniList GraphQL field set ─────────────────────────────────────────────
    private const val MEDIA_FIELDS = """
        id
        title { romaji english native }
        coverImage { large extraLarge color }
        bannerImage
        format
        status
        episodes
        duration
        averageScore
        popularity
        description(asHtml: false)
        genres
        seasonYear
        season
        startDate { year month day }
        isAdult
        studios(isMain: true) { nodes { name } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        streamingEpisodes { title thumbnail url site }
    """

    // ── GraphQL query helper ──────────────────────────────────────────────────
    private suspend fun query(query: String, vars: Map<String, Any?> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("query", query).put("variables", JSONObject(vars)).toString()
            var attempt = 0
            val maxAttempts = 3
            while (true) {
                attempt++
                val conn = (URL(GQL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray()) }
                }

                val code = runCatching { conn.responseCode }.getOrElse { 500 }
                if (code == 429 || code >= 500) {
                    if (attempt >= maxAttempts) throw Exception("AniList HTTP $code after $attempt attempts")
                    val retryAfterStr = conn.getHeaderField("Retry-After")
                    val delayMs = (retryAfterStr?.toLongOrNull() ?: 1L) * 1000L
                    kotlinx.coroutines.delay(delayMs.coerceAtLeast(2000L * attempt)) // Exponential fallback
                    continue
                }

                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: "{}"

                val data = runCatching { JSONObject(resp) }.getOrElse { JSONObject() }
                if (data.has("errors")) {
                    throw Exception("AniList: ${data.getJSONArray("errors")}")
                }
                if (data.has("data")) {
                    return@withContext data.getJSONObject("data")
                }
                if (attempt >= maxAttempts) throw Exception("AniList invalid response: $resp")
                kotlinx.coroutines.delay(2000L * attempt)
            }
            throw Exception("AniList query failed")
        }

    private fun parseMediaArray(arr: JSONArray): List<AnimeCard> {
        val result = mutableListOf<AnimeCard>()
        for (i in 0 until arr.length()) {
            runCatching { result.add(AnimeCard.fromJson(arr.getJSONObject(i).toMap())) }
        }
        return result
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            map[key] = when (val v = get(key)) {
                is JSONObject -> v.toMap()
                is JSONArray  -> v.toList()
                JSONObject.NULL -> null
                else          -> v
            }
        }
        return map
    }

    private fun JSONArray.toList(): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until length()) {
            list.add(when (val v = get(i)) {
                is JSONObject -> v.toMap()
                is JSONArray  -> v.toList()
                JSONObject.NULL -> null
                else          -> v
            })
        }
        return list
    }

    // ── AniList list helpers ──────────────────────────────────────────────────
    private suspend fun list(
        sort: String,
        page: Int = 1,
        perPage: Int = 20,
        extraFilter: String = "",
    ): List<AnimeCard> {
        val filter = if (extraFilter.isNotEmpty()) ", $extraFilter" else ""
        val q = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: [$sort], isAdult: false$filter) {
                  $MEDIA_FIELDS
                }
              }
            }
        """
        val data = query(q, mapOf("page" to page, "perPage" to perPage))
        return parseMediaArray(data.getJSONObject("Page").getJSONArray("media"))
    }

    // ── Public list APIs ──────────────────────────────────────────────────────
    suspend fun getSpotlight(perPage: Int = 10): List<AnimeCard> =
        list("TRENDING_DESC", perPage = perPage, extraFilter = "status_in: [RELEASING, FINISHED]")

    suspend fun getTrending(perPage: Int = 20): List<AnimeCard> =
        list("TRENDING_DESC", perPage = perPage)

    suspend fun getTopAiring(perPage: Int = 20): List<AnimeCard> =
        list("POPULARITY_DESC", perPage = perPage, extraFilter = "status: RELEASING")

    suspend fun getMostPopular(perPage: Int = 20): List<AnimeCard> =
        list("POPULARITY_DESC", perPage = perPage)

    suspend fun getMostFavorite(perPage: Int = 20): List<AnimeCard> =
        list("FAVOURITES_DESC", perPage = perPage)

    suspend fun getTopRated(perPage: Int = 20): List<AnimeCard> =
        list("SCORE_DESC", perPage = perPage)

    suspend fun getLatestCompleted(perPage: Int = 20): List<AnimeCard> =
        list("END_DATE_DESC", perPage = perPage, extraFilter = "status: FINISHED")

    suspend fun getRecentEpisodes(perPage: Int = 20): List<AnimeCard> =
        list("UPDATED_AT_DESC", perPage = perPage, extraFilter = "status: RELEASING")

    suspend fun getTop10Today(perPage: Int = 10): List<AnimeCard> =
        list("TRENDING_DESC", perPage = perPage)

    // ── Seasons chain ───────────────────────────────────────────────────────
    // Assemble the ordered list of numbered seasons for a show by walking the
    // AniList relation graph: back to the root via PREQUEL/PARENT, then forward
    // via SEQUEL. Only chains through TV/TV_SHORT/ONA (movies/specials are side
    // material, not seasons). Always includes the input anime. Ported from the
    // mobile app's AnimeService.getSeasons.
    suspend fun getSeasons(anilistId: Int): List<AnimeCard> {
        val q = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_FIELDS
                relations { edges { relationType node { id type format } } }
              }
            }
        """
        val fetched = HashMap<Int, JSONObject>()
        suspend fun fetch(id: Int): JSONObject? {
            fetched[id]?.let { return it }
            return try {
                val media = query(q, mapOf("id" to id)).optJSONObject("Media") ?: return null
                fetched[id] = media
                media
            } catch (e: Exception) {
                Log.w(TAG, "[Seasons] fetch $id failed: ${e.message}")
                null
            }
        }
        fun neighbor(media: JSONObject, wanted: Set<String>): Int? {
            val edges = media.optJSONObject("relations")?.optJSONArray("edges") ?: return null
            for (i in 0 until edges.length()) {
                val e = edges.optJSONObject(i) ?: continue
                if (e.optString("relationType") !in wanted) continue
                val node = e.optJSONObject("node") ?: continue
                if (node.optString("type") != "ANIME") continue
                if (node.optString("format") !in setOf("TV", "TV_SHORT", "ONA")) continue
                if (node.has("id")) return node.optInt("id")
            }
            return null
        }

        val visited = hashSetOf(anilistId)
        var rootId = anilistId
        val root = fetch(anilistId)
            ?: return try { listOf(getDetails(anilistId)) } catch (_: Exception) { emptyList() }

        // 1. Walk to root via PREQUEL/PARENT.
        var current = root
        while (true) {
            val p = neighbor(current, setOf("PREQUEL", "PARENT")) ?: break
            if (!visited.add(p)) break
            current = fetch(p) ?: break
            rootId = p
        }
        // 2. Walk forward from root via SEQUEL.
        val chain = mutableListOf(rootId)
        current = fetch(rootId)!!
        while (true) {
            val s = neighbor(current, setOf("SEQUEL")) ?: break
            if (!visited.add(s)) break
            current = fetch(s) ?: break
            chain.add(s)
        }
        // 3. Always include the input anime.
        if (!chain.contains(anilistId)) chain.add(anilistId)

        return chain.mapNotNull { fetched[it] }.map { AnimeCard.fromJson(it.toMap()) }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    suspend fun search(term: String, page: Int = 1, perPage: Int = 30): List<AnimeCard> {
        if (term.isBlank()) return emptyList()
        val q = """
            query (${'$'}q: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, search: ${'$'}q, sort: [SEARCH_MATCH, POPULARITY_DESC]) {
                  $MEDIA_FIELDS
                }
              }
            }
        """
        val data = query(q, mapOf("q" to term, "page" to page, "perPage" to perPage))
        return parseMediaArray(data.getJSONObject("Page").getJSONArray("media"))
    }

    // ── Details ───────────────────────────────────────────────────────────────
    suspend fun getDetails(anilistId: Int): AnimeCard {
        val q = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $MEDIA_FIELDS }
            }
        """
        val data = query(q, mapOf("id" to anilistId))
        return AnimeCard.fromJson(data.getJSONObject("Media").toMap())
    }

    // ── Relations ─────────────────────────────────────────────────────────────
    suspend fun getRelations(anilistId: Int): List<AnimeCard> {
        val animeFormats = setOf("TV", "TV_SHORT", "MOVIE", "OVA", "ONA", "SPECIAL", "MUSIC")
        val q = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                relations { nodes { $MEDIA_FIELDS } }
              }
            }
        """
        val data = query(q, mapOf("id" to anilistId))
        val nodes = data.getJSONObject("Media").optJSONObject("relations")
            ?.optJSONArray("nodes") ?: return emptyList()
        return parseMediaArray(nodes).filter { it.format in animeFormats }
    }

    // ── Browse ────────────────────────────────────────────────────────────────
    suspend fun browse(
        genre: String? = null,
        year: Int? = null,
        season: String? = null,
        format: String? = null,
        status: String? = null,
        sort: String = "POPULARITY_DESC",
        page: Int = 1,
        perPage: Int = 30,
    ): List<AnimeCard> {
        val filters = mutableListOf<String>()
        if (!genre.isNullOrBlank()) filters += "genre_in: [\"$genre\"]"
        if (year != null) filters += "seasonYear: $year"
        if (!season.isNullOrBlank()) filters += "season: $season"
        if (!format.isNullOrBlank()) filters += "format: $format"
        if (!status.isNullOrBlank()) filters += "status: $status"
        val extra = if (filters.isNotEmpty()) ", ${filters.joinToString(", ")}" else ""
        val isAdult = genre?.lowercase() == "hentai"

        val q = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: [$sort], isAdult: $isAdult$extra) {
                  $MEDIA_FIELDS
                }
              }
            }
        """
        val data = query(q, mapOf("page" to page, "perPage" to perPage))
        return parseMediaArray(data.getJSONObject("Page").getJSONArray("media"))
    }

    // ── Anikoto resolution (episode embed IDs) ────────────────────────────────
    private val anikotoCache = mutableMapOf<Int, AnikotoSeries?>()

    suspend fun resolveAnikoto(anime: AnimeCard): AnikotoSeries? {
        if (anikotoCache.containsKey(anime.id)) return anikotoCache[anime.id]
        val s = findAnikotoSeries(anime)
        anikotoCache[anime.id] = s
        return s
    }

    private suspend fun findAnikotoSeries(anime: AnimeCard): AnikotoSeries? =
        withContext(Dispatchers.IO) {
            // Strategy A: walk /recent-anime feed
            for (page in 1..6) {
                try {
                    val j = anikotoGet("/recent-anime?page=$page&per_page=60") ?: break
                    val data = j.optJSONArray("data") ?: break
                    for (i in 0 until data.length()) {
                        val m = data.getJSONObject(i)
                        if (m.optString("ani_id") == anime.id.toString()) {
                            return@withContext loadAnikotoSeries(m.getInt("id"))
                        }
                    }
                    if (data.length() < 60) break
                } catch (e: Exception) {
                    Log.d(TAG, "[Anikoto] page $page failed: ${e.message}")
                    break
                }
            }

            // Strategy B: HTML search + slug→ID probe
            val candidates = linkedSetOf<String>()
            for (q in listOf(anime.titleEnglish, anime.titleRomaji).filter { it.isNotBlank() }.distinct()) {
                candidates += anikotoSearchSlugs(q)
                if (candidates.size >= 10) break
            }

            data class Candidate(val slug: String, val id: Int, val episodes: Int)
            val aniIdMatches = mutableListOf<Candidate>()
            val resolved = mutableListOf<Candidate>()
            for (slug in candidates.take(8)) {
                val id = anikotoIdFromSlug(slug) ?: continue
                try {
                    val j = anikotoGet("/series/$id") ?: continue
                    val aniId = j.optJSONObject("data")?.optJSONObject("anime")?.optString("ani_id") ?: ""
                    val epCount = j.optJSONObject("data")?.optJSONArray("episodes")?.length() ?: 0
                    val cand = Candidate(slug, id, epCount)
                    if (aniId == anime.id.toString()) aniIdMatches += cand else resolved += cand
                } catch (_: Exception) {}
            }

            if (aniIdMatches.isNotEmpty()) {
                val expected = anime.episodes ?: 0
                val best = if (expected > 0) {
                    aniIdMatches.minByOrNull { Math.abs(it.episodes - expected) }!!
                } else {
                    aniIdMatches.maxByOrNull { it.episodes }!!
                }
                return@withContext loadAnikotoSeries(best.id)
            }

            // Strategy C: fuzzy slug score
            if (resolved.isNotEmpty()) {
                val titleTokens = listOf(anime.titleEnglish, anime.titleRomaji)
                    .flatMap { slugTokens(it) }
                    .toMutableSet()
                titleTokens -= ANIKOTO_STOPWORDS
                if (titleTokens.isNotEmpty()) {
                    var best: Candidate? = null
                    var bestScore = 0.0
                    for (c in resolved) {
                        val slugToks = c.slug.split("-")
                            .filter { it.length > 1 && !Regex("^[a-z0-9]{5}$").matches(it) }
                            .toMutableSet()
                        slugToks -= ANIKOTO_STOPWORDS
                        if (slugToks.isEmpty()) continue
                        val inter = (slugToks intersect titleTokens).size
                        if (inter == 0) continue
                        val score = inter.toDouble() / (slugToks.size + titleTokens.size - inter)
                        if (score > bestScore) { bestScore = score; best = c }
                    }
                    if (best != null && bestScore >= 0.40) {
                        return@withContext loadAnikotoSeries(best.id)
                    }
                }
            }
            null
        }

    private val ANIKOTO_STOPWORDS = setOf(
        "the", "a", "an", "of", "and", "or", "to", "in", "on",
        "no", "wa", "ga", "ni", "wo", "de", "mo",
        "season", "part", "arc", "tv", "special", "ova", "ona",
    )

    private fun slugTokens(s: String): Set<String> = s.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 1 }
        .toSet()

    private suspend fun anikotoSearchSlugs(q: String): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://anikototv.to/search?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html")
                    connectTimeout = 10_000; readTimeout = 10_000
                }
                if (conn.responseCode != 200) return@withContext emptyList()
                val html = conn.inputStream.bufferedReader().readText()
                val seen = linkedSetOf<String>()
                Regex("/watch/([a-z0-9-]+)").findAll(html).forEach { m ->
                    if (seen.size < 12) seen += m.groupValues[1]
                }
                seen.toList()
            } catch (e: Exception) {
                Log.d(TAG, "[Anikoto] search \"$q\" failed: ${e.message}")
                emptyList()
            }
        }

    private suspend fun anikotoIdFromSlug(slug: String): Int? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL("https://anikototv.to/watch/$slug").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html")
                    connectTimeout = 10_000; readTimeout = 10_000
                }
                if (conn.responseCode != 200) return@withContext null
                val html = conn.inputStream.bufferedReader().readText()
                Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            } catch (e: Exception) {
                Log.d(TAG, "[Anikoto] watch/$slug failed: ${e.message}")
                null
            }
        }

    private suspend fun loadAnikotoSeries(id: Int): AnikotoSeries? =
        withContext(Dispatchers.IO) {
            try {
                val j = anikotoGet("/series/$id") ?: return@withContext null
                val eps = j.optJSONObject("data")?.optJSONArray("episodes")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            runCatching {
                                AnikotoEpisode.fromJson(arr.getJSONObject(i).toMap())
                            }.getOrNull()
                        }
                    } ?: emptyList()
                AnikotoSeries(id = id, episodes = eps)
            } catch (e: Exception) {
                Log.d(TAG, "[Anikoto] /series/$id failed: ${e.message}")
                null
            }
        }

    private suspend fun anikotoGet(path: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL("https://anikotoapi.site$path").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 12_000; readTimeout = 12_000
                }
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body)
            } catch (e: Exception) {
                Log.d(TAG, "[Anikoto] GET $path failed: ${e.message}")
                null
            }
        }

    // ── Episodes ──────────────────────────────────────────────────────────────
    suspend fun getEpisodes(anime: AnimeCard): List<AnimeEpisode> {
        var fresh = anime
        runCatching { fresh = getDetails(anime.id) }
        val thumbMap = buildThumbnailMap(fresh.streamingEpisodes)

        // 1. Try Anikoto
        val series = resolveAnikoto(anime)
        if (series != null && series.episodes.isNotEmpty()) {
            return series.episodes.map { e ->
                AnimeEpisode(
                    number = e.number,
                    title = e.title.ifBlank { "Episode ${e.number}" },
                    aired = true,
                    thumbnail = thumbMap[e.number],
                )
            }
        }

        // 2. Fallback: synthesise from AniList total
        val count = fresh.episodes ?: anime.episodes
            ?: fresh.nextAiringEpisode?.get("episode")
            ?: anime.nextAiringEpisode?.get("episode")
        val n = if (count is Int && count > 0) count else 1
        val airedNow = fresh.nextAiringEpisode?.get("episode")
        val maxAvailable = if (airedNow is Int && airedNow > 1) airedNow - 1 else n
        return List(n) { i ->
            AnimeEpisode(
                number = i + 1,
                title = "Episode ${i + 1}",
                aired = (i + 1) <= maxAvailable,
                thumbnail = thumbMap[i + 1],
            )
        }
    }

    private fun buildThumbnailMap(streamEps: List<Map<String, String>>): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        val reEp = Regex("(?:episode|ep|e)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        var seq = 1
        for (m in streamEps) {
            val thumb = m["thumbnail"]?.trim() ?: ""
            if (thumb.isEmpty()) { seq++; continue }
            val num = reEp.find(m["title"] ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: seq
            out[num] = thumb
            seq++
        }
        return out
    }

    // ── Embed building ────────────────────────────────────────────────────────
    fun buildAllEmbeds(
        anilistId: Int,
        episode: Int,
        series: AnikotoSeries? = null,
        category: String? = null,
        animeTitles: List<String> = emptyList(),
        isAdult: Boolean = false,
    ): List<AnimeEmbed> {
        val embedId = series?.episodes?.firstOrNull { it.number == episode }?.embedId
        val all = mutableListOf<AnimeEmbed>()

        if (!embedId.isNullOrBlank()) {
            all += AnimeEmbed("HD-1", "megaplay", "sub",
                "https://megaplay.buzz/stream/s-2/$embedId/sub?autoPlay=1")
            all += AnimeEmbed("HD-2", "vidwish",  "sub",
                "https://vidwish.live/stream/s-2/$embedId/sub?autoPlay=1")
            all += AnimeEmbed("HD-1", "megaplay", "dub",
                "https://megaplay.buzz/stream/s-2/$embedId/dub?autoPlay=1")
            all += AnimeEmbed("HD-2", "vidwish",  "dub",
                "https://vidwish.live/stream/s-2/$embedId/dub?autoPlay=1")
        }

        // Miruro fallback
        for (cat in listOf("sub", "dub")) {
            for (prov in MiruroExtractor.KNOWN_PROVIDERS) {
                all += AnimeEmbed("Miruro·$prov", "miruro", cat,
                    "miruro://anilist/$anilistId/$episode/$cat/$prov")
            }
        }

        // AllAnime fallback
        val titles = animeTitles.filter { it.isNotBlank() }
            .joinToString(",") { java.net.URLEncoder.encode(it.trim(), "UTF-8") }
        if (titles.isNotEmpty()) {
            for (cat in listOf("sub", "dub")) {
                for (prov in AllAnimeExtractor.KNOWN_PROVIDERS) {
                    all += AnimeEmbed("AllAnime·$prov", "allanime", cat,
                        "allanime://search/$episode/$cat/$prov?t=$titles")
                }
            }
        }

        // Adult-only sources
        if (isAdult && titles.isNotEmpty()) {
            all += AnimeEmbed("WatchHentai", "watchhentai", "sub",
                "watchhentai://discover/$episode?t=$titles")
            all += AnimeEmbed("Hentaini", "hentaini", "sub",
                "hentaini://discover/$episode?t=$titles")
        }

        return if (category == null) all else all.filter { it.category == category }
    }

    // ── Direct stream extraction ──────────────────────────────────────────────
    suspend fun extractDirect(embed: AnimeEmbed): AnimeStreamResult? {
        return when (embed.server) {
            "miruro"      -> MiruroExtractor.extract(embed)
            "allanime"    -> AllAnimeExtractor.extract(embed)
            "watchhentai" -> WatchHentaiExtractor.extract(embed)
            "hentaini"    -> HentainiExtractor.extract(embed)
            else          -> extractMegaplay(embed)
        }
    }

    private suspend fun extractMegaplay(embed: AnimeEmbed): AnimeStreamResult? =
        withContext(Dispatchers.IO) {
            try {
                val embedUri = URL(embed.url)
                val origin = "${embedUri.protocol}://${embedUri.host}"

                // Step 1: fetch embed HTML → data-id
                val pageConn = (embedUri.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Referer", EMBED_REFERER)
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                    connectTimeout = 12_000; readTimeout = 12_000
                }
                if (pageConn.responseCode != 200) return@withContext null
                val html = pageConn.inputStream.bufferedReader().readText()
                val dataId = Regex("""data-id\s*=\s*"(\d+)"""").find(html)?.groupValues?.get(1)
                    ?: return@withContext null

                // Step 2: fetch sources JSON
                val apiConn = (URL("$origin/stream/getSources?id=$dataId").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Referer", embed.url)
                    setRequestProperty("Origin", origin)
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    connectTimeout = 12_000; readTimeout = 12_000
                }
                if (apiConn.responseCode != 200) return@withContext null
                val json = JSONObject(apiConn.inputStream.bufferedReader().readText())
                val file = (json.optJSONObject("sources") ?: json).optString("file", "")
                    .ifBlank { return@withContext null }
                val tracks = mutableListOf<AnimeTrack>()
                json.optJSONArray("tracks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val kind = t.optString("kind", "captions")
                        if ((kind == "captions" || kind == "subtitles") && t.has("file")) {
                            tracks += AnimeTrack(
                                url = t.getString("file"),
                                label = t.optString("label", "Unknown"),
                                isDefault = t.optBoolean("default", false),
                            )
                        }
                    }
                }
                AnimeStreamResult(url = file, referer = "$origin/", origin = origin, tracks = tracks)
            } catch (e: Exception) {
                Log.d(TAG, "[extractMegaplay] error: ${e.message}")
                null
            }
        }
}

