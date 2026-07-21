package com.playtorrio.tv.ui.screens.anime

import android.content.SharedPreferences
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playtorrio.tv.data.anime.AnimeCard
import com.playtorrio.tv.data.anime.AnimeEmbed
import com.playtorrio.tv.data.anime.AnimeEpisode
import com.playtorrio.tv.data.anime.AnimeService
import com.playtorrio.tv.data.anime.AnimeStreamResult
import com.playtorrio.tv.data.anime.AnikotoSeries
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AnimeViewModel : ViewModel() {

    // ── Home screen state ─────────────────────────────────────────────────────
    val spotlight    = MutableStateFlow<List<AnimeCard>>(emptyList())
    val top10        = MutableStateFlow<List<AnimeCard>>(emptyList())
    val trending     = MutableStateFlow<List<AnimeCard>>(emptyList())
    val topAiring    = MutableStateFlow<List<AnimeCard>>(emptyList())
    val mostPopular  = MutableStateFlow<List<AnimeCard>>(emptyList())
    val topRated     = MutableStateFlow<List<AnimeCard>>(emptyList())
    val latestDone   = MutableStateFlow<List<AnimeCard>>(emptyList())
    val recentEps    = MutableStateFlow<List<AnimeCard>>(emptyList())
    val hentai       = MutableStateFlow<List<AnimeCard>>(emptyList())
    val isLoading    = MutableStateFlow(true)
    val homeError    = MutableStateFlow<String?>(null)

    // ── Detail screen state ───────────────────────────────────────────────────
    val selectedAnime   = MutableStateFlow<AnimeCard?>(null)
    val episodes        = MutableStateFlow<List<AnimeEpisode>>(emptyList())
    val relations       = MutableStateFlow<List<AnimeCard>>(emptyList())
    val seasons         = MutableStateFlow<List<AnimeCard>>(emptyList())
    val resolvedSeries  = MutableStateFlow<AnikotoSeries?>(null)
    val detailLoading   = MutableStateFlow(false)
    val episodesPage    = MutableStateFlow(0)
    val episodePageSize = 50

    // ── Player state ──────────────────────────────────────────────────────────
    val embeds           = MutableStateFlow<List<AnimeEmbed>>(emptyList())
    val streamResult     = MutableStateFlow<AnimeStreamResult?>(null)
    val streamLoading    = MutableStateFlow(false)
    val streamError      = MutableStateFlow<String?>(null)
    val selectedCategory = MutableStateFlow("sub")

    // ── Auto-extract (tap episode → race all sources → auto-play) ─────────────
    val autoExtractLoading = MutableStateFlow(false)
    val autoExtractResult  = MutableStateFlow<AnimeStreamResult?>(null)
    val autoExtractWinningEmbed = MutableStateFlow<AnimeEmbed?>(null)
    val autoExtractError   = MutableStateFlow<String?>(null)
    val autoExtractEmbeds  = MutableStateFlow<List<AnimeEmbed>>(emptyList()) // for in-player switcher

    // ── Search ────────────────────────────────────────────────────────────────
    val searchQuery     = MutableStateFlow("")
    val searchResults   = MutableStateFlow<List<AnimeCard>>(emptyList())
    val searchLoading   = MutableStateFlow(false)

    // ── Liked / history ───────────────────────────────────────────────────────
    val likedIds        = MutableStateFlow<Set<Int>>(emptySet())
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("anime_prefs_v1", Context.MODE_PRIVATE)
        loadLiked()
        // Only fetch home rails when we don't already have them — the VM survives
        // back-navigation, so returning to the Anime screen is instant instead of
        // re-downloading all rails.
        if (spotlight.value.isEmpty() && trending.value.isEmpty() && !isLoading.value) {
            loadHome()
        } else {
            // Keep the mature rail in sync with the 18+ setting without a full reload.
            val adult = com.playtorrio.tv.data.AppPreferences.showAdultContent
            if (adult && hentai.value.isEmpty()) {
                viewModelScope.launch {
                    hentai.value = runCatching {
                        AnimeService.browse(genre = "Hentai", sort = "POPULARITY_DESC", perPage = 20)
                    }.getOrDefault(emptyList())
                }
            } else if (!adult && hentai.value.isNotEmpty()) {
                hentai.value = emptyList()
            }
        }
    }

    // ── Home ──────────────────────────────────────────────────────────────────
    fun loadHome() {
        viewModelScope.launch {
            isLoading.value = true
            homeError.value = null
            try {
                val sp = async { runCatching { AnimeService.getSpotlight(10) }.getOrDefault(emptyList()) }
                val t10 = async { runCatching { AnimeService.getTop10Today(10) }.getOrDefault(emptyList()) }
                val tr = async { runCatching { AnimeService.getTrending(20) }.getOrDefault(emptyList()) }
                val ta = async { runCatching { AnimeService.getTopAiring(20) }.getOrDefault(emptyList()) }
                val mp = async { runCatching { AnimeService.getMostPopular(20) }.getOrDefault(emptyList()) }
                val rt = async { runCatching { AnimeService.getTopRated(20) }.getOrDefault(emptyList()) }
                val lc = async { runCatching { AnimeService.getLatestCompleted(20) }.getOrDefault(emptyList()) }
                val re = async { runCatching { AnimeService.getRecentEpisodes(20) }.getOrDefault(emptyList()) }
                val he = async {
                    if (com.playtorrio.tv.data.AppPreferences.showAdultContent)
                        runCatching { AnimeService.browse(genre = "Hentai", sort = "POPULARITY_DESC", perPage = 20) }.getOrDefault(emptyList())
                    else emptyList()
                }
                spotlight.value   = sp.await()
                top10.value       = t10.await()
                trending.value    = tr.await()
                topAiring.value   = ta.await()
                mostPopular.value = mp.await()
                topRated.value    = rt.await()
                latestDone.value  = lc.await()
                recentEps.value   = re.await()
                hentai.value      = he.await()
            } catch (e: Exception) {
                homeError.value = e.message
            } finally {
                isLoading.value = false
            }
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────────
    fun loadDetail(anime: AnimeCard) {
        selectedAnime.value = anime
        episodes.value = emptyList()
        relations.value = emptyList()
        seasons.value = emptyList()
        resolvedSeries.value = null
        episodesPage.value = 0
        detailLoading.value = true

        viewModelScope.launch {
            try {
                val fresh = runCatching { AnimeService.getDetails(anime.id) }.getOrDefault(anime)
                selectedAnime.value = fresh

                val epList = async { runCatching { AnimeService.getEpisodes(fresh) }.getOrDefault(emptyList()) }
                val relList = async { runCatching { AnimeService.getRelations(fresh.id) }.getOrDefault(emptyList()) }
                val seaList = async { runCatching { AnimeService.getSeasons(fresh.id) }.getOrDefault(emptyList()) }
                val ser = async { runCatching { AnimeService.resolveAnikoto(fresh) }.getOrNull() }

                episodes.value       = epList.await()
                relations.value      = relList.await()
                seasons.value        = seaList.await().takeIf { it.size > 1 } ?: emptyList()
                resolvedSeries.value = ser.await()
            } catch (_: Exception) {
            } finally {
                detailLoading.value = false
            }
        }
    }

    fun episodesForPage(): List<AnimeEpisode> {
        val all = episodes.value
        val start = episodesPage.value * episodePageSize
        val end = minOf(start + episodePageSize, all.size)
        return if (start >= all.size) emptyList() else all.subList(start, end)
    }

    val totalEpisodePages: Int get() =
        if (episodes.value.isEmpty()) 1
        else ((episodes.value.size - 1) / episodePageSize) + 1

    fun nextEpisodePage() { if (episodesPage.value < totalEpisodePages - 1) episodesPage.value++ }
    fun prevEpisodePage() { if (episodesPage.value > 0) episodesPage.value-- }

    // ── Stream ────────────────────────────────────────────────────────────────
    fun loadEmbeds(episode: Int, category: String = selectedCategory.value) {
        val anime = selectedAnime.value ?: return
        val titles = listOf(anime.titleEnglish, anime.titleRomaji, anime.titleNative)
            .filter { it.isNotBlank() }.distinct()
        embeds.value = AnimeService.buildAllEmbeds(
            anilistId   = anime.id,
            episode     = episode,
            series      = resolvedSeries.value,
            category    = category,
            animeTitles = titles,
            isAdult     = anime.isAdult,
        )
        streamResult.value = null
        streamError.value  = null
    }

    fun extractStream(embed: AnimeEmbed) {
        streamLoading.value = true
        streamError.value   = null
        viewModelScope.launch {
            val result = runCatching { AnimeService.extractDirect(embed) }.getOrNull()
            if (result != null) streamResult.value = result
            else streamError.value = "Could not extract stream from ${embed.displayName}"
            streamLoading.value = false
        }
    }

    // -- AUTO-EXTRACT: tap episode ? race all sources ? first win plays --------
    fun clearExtractResult() {
        autoExtractResult.value = null
    }

    fun autoExtractFirst(episode: Int, category: String = selectedCategory.value) {
        val anime = selectedAnime.value ?: run {
            android.util.Log.e("AnimeVM", "autoExtractFirst: selectedAnime is NULL")
            return
        }
        android.util.Log.d("AnimeVM", "autoExtractFirst: anime= ep= cat=")
        autoExtractLoading.value = true
        autoExtractResult.value  = null
        autoExtractWinningEmbed.value = null
        autoExtractError.value   = null

        viewModelScope.launch {
            try {
                // 1. Build embeds list
                val titles = listOf(anime.titleEnglish, anime.titleRomaji, anime.titleNative)
                    .filter { it.isNotBlank() }.distinct()
                android.util.Log.d("AnimeVM", "autoExtractFirst: titles=, series=")
                val allEmbeds = AnimeService.buildAllEmbeds(
                    anilistId   = anime.id,
                    episode     = episode,
                    series      = resolvedSeries.value,
                    category    = null,
                    animeTitles = titles,
                    isAdult     = anime.isAdult,
                )
                autoExtractEmbeds.value = allEmbeds
                android.util.Log.d("AnimeVM", "autoExtractFirst:  embeds built")
                allEmbeds.forEachIndexed { idx, e -> android.util.Log.d("AnimeVM", "  embed[]: / cat=") }

                val targetEmbeds = allEmbeds.filter { it.category == category }
                if (targetEmbeds.isEmpty()) {
                    android.util.Log.e("AnimeVM", "autoExtractFirst: NO EMBEDS found for category ")
                    autoExtractError.value  = "No sources found for episode  ()"
                    autoExtractLoading.value = false
                    return@launch
                }

                // 2. Race all sources in parallel - first non-null result wins
                var winner: AnimeStreamResult? = null
                var winningEmbed: AnimeEmbed? = null
                val jobs = targetEmbeds.map { embed ->
                    async {
                        android.util.Log.d("AnimeVM", "Extracting: /...")
                        val r = runCatching { AnimeService.extractDirect(embed) }
                        if (r.isFailure) android.util.Log.e("AnimeVM", "Extract FAILED : ")
                        else android.util.Log.d("AnimeVM", "Extract OK : url=")
                        embed to r.getOrNull()
                    }
                }
                for (deferred in jobs) {
                    val (emb, result) = deferred.await()
                    if (result != null && winner == null) {
                        winner = result
                        winningEmbed = emb
                        android.util.Log.d("AnimeVM", "WINNER: ")
                        jobs.forEach { it.cancel() }
                        break
                    }
                }

                autoExtractWinningEmbed.value = winningEmbed
                autoExtractResult.value  = winner
                autoExtractError.value   = if (winner == null) "All sources failed for episode " else null
                autoExtractLoading.value = false
                android.util.Log.d("AnimeVM", "autoExtractFirst DONE: winner=")
            } catch (e: Exception) {
                android.util.Log.e("AnimeVM", "autoExtractFirst EXCEPTION", e)
                autoExtractError.value   = e.message ?: "Extraction crashed"
                autoExtractLoading.value = false
            }
        }
    }

    /** Switch to a different embed from inside the player. */
    fun switchSource(embed: com.playtorrio.tv.data.anime.AnimeEmbed) {
        autoExtractLoading.value = true
        autoExtractResult.value  = null
        autoExtractError.value   = null
        viewModelScope.launch {
            val result = runCatching { AnimeService.extractDirect(embed) }.getOrNull()
            autoExtractResult.value  = result
            autoExtractError.value   = if (result == null) "Failed to extract ${embed.displayName}" else null
            autoExtractLoading.value = false
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    fun search(query: String) {
        if (query.isBlank()) { searchResults.value = emptyList(); return }
        searchLoading.value = true
        viewModelScope.launch {
            searchResults.value = runCatching { AnimeService.search(query) }.getOrDefault(emptyList())
            searchLoading.value = false
        }
    }

    // ── Liked ─────────────────────────────────────────────────────────────────
    private val LIKED_KEY = "liked_ids_v1"

    private fun loadLiked() {
        val raw = prefs?.getString(LIKED_KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        likedIds.value = (0 until arr.length()).map { arr.getInt(it) }.toSet()
    }

    fun toggleLike(anime: AnimeCard) {
        val current = likedIds.value.toMutableSet()
        if (anime.id in current) current.remove(anime.id) else current.add(anime.id)
        likedIds.value = current
        val arr = JSONArray().also { a -> current.forEach { a.put(it) } }
        prefs?.edit()?.putString(LIKED_KEY, arr.toString())?.apply()
    }

    fun isLiked(id: Int) = id in likedIds.value
}
