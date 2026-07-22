package com.playtorrio.tv.ui.screens.player

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import com.playtorrio.tv.data.AppPreferences
import com.playtorrio.tv.data.playback.PlaybackQueue
import com.playtorrio.tv.data.debrid.DebridResolver
import com.playtorrio.tv.data.streaming.StreamExtractorService
import com.playtorrio.tv.data.skip.SkipSegment
import com.playtorrio.tv.data.skip.SkipSegmentService
import com.playtorrio.tv.data.subtitle.ExternalSubtitle
import com.playtorrio.tv.data.subtitle.SubtitleCue
import com.playtorrio.tv.data.subtitle.SubtitleCueParser
import com.playtorrio.tv.data.subtitle.SubtitleService
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import com.playtorrio.tv.data.stremio.StremioService
import com.playtorrio.tv.data.api.TmdbClient
import com.playtorrio.tv.data.torrent.TorrServerService
import com.playtorrio.tv.data.watch.WatchKind
import com.playtorrio.tv.data.watch.WatchProgress
import com.playtorrio.tv.data.watch.WatchProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AudioTrackInfo(
    val index: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String?,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val sampleRate: Int?,
    val isSelected: Boolean
)

data class VideoTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?,
    val frameRate: Float?,
    val codec: String?,
    val isSelected: Boolean
) {
    /** Human label e.g. "1080p" or "720p" — falls back to bitrate / index. */
    fun displayName(): String = when {
        height != null && height > 0 -> "${height}p"
        bitrate != null && bitrate > 0 -> "${bitrate / 1000} kbps"
        else -> "Track ${trackIndex + 1}"
    }

    fun metadata(): String = listOfNotNull(
        width?.let { w -> height?.let { h -> "${w}\u00d7${h}" } },
        bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        frameRate?.takeIf { it > 0f }?.let { "%.0f fps".format(it) },
        codec,
    ).joinToString(" \u2022 ")
}

data class SubtitleTrackInfo(
    val id: String,
    val label: String,
    val language: String?,
    val isBuiltIn: Boolean,
    val isSelected: Boolean,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val externalUrl: String? = null,
    val source: String = ""
)

enum class AspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM_115("Zoom 1.15x"),
    ZOOM_133("Zoom 1.33x")
}

data class SubtitleStyleSettings(
    val size: Float = 100f,
    val textColor: Int = android.graphics.Color.WHITE,
    val backgroundColor: Int = android.graphics.Color.TRANSPARENT,
    val outlineEnabled: Boolean = true,
    val outlineColor: Int = android.graphics.Color.BLACK,
    val bold: Boolean = false,
    val verticalOffset: Float = 0f,
    val subtitleDelayMs: Long = 0L
)

data class PlayerUiState(
    // Loading / connection
    val isConnecting: Boolean = true,
    val connectionStatus: String = "Starting TorrServer…",
    /** True while IPTV stall recovery is in flight — keeps the video surface
     *  visible (no black LoadingOverlay) and shows just a buffering spinner. */
    val isReconnecting: Boolean = false,
    val reconnectStatus: String = "",
    // Playback
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0L,
    val currentPosition: Long = 0L,
    // Seek preview
    val pendingPreviewSeekPosition: Long? = null,
    val showSeekOverlay: Boolean = false,
    // Metadata
    val title: String = "",
    val logoUrl: String? = null,
    val backdropUrl: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val overview: String? = null,
    val isMovie: Boolean = true,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val tmdbId: Int = 0,
    // Torrent
    val torrentHash: String? = null,
    val speedMbps: Double = 0.0,
    val activePeers: Int = 0,
    // Tracks
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val videoTracks: List<VideoTrackInfo> = emptyList(),
    /** True when ExoPlayer is free to adapt; false when user/auto-highest pinned a variant. */
    val isQualityAuto: Boolean = true,
    // Aspect
    val aspectMode: AspectMode = AspectMode.FIT,
    val showAspectIndicator: Boolean = false,
    // Playback speed (1.0 = normal)
    val playbackSpeed: Float = 1.0f,
    // Subtitle style
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    /** Cues of the currently active external subtitle (rendered by Compose
     *  overlay so subtitle delay can be applied instantly). */
    val customSubtitleCues: List<SubtitleCue> = emptyList(),
    val customSubtitleLabel: String? = null,
    val customSubtitleSourceUrl: String? = null,
    val customSubtitleLoading: Boolean = false,
    // Skip segments
    val skipSegments: List<SkipSegment> = emptyList(),
    val activeSkipSegment: SkipSegment? = null,
    // Overlays
    val showControls: Boolean = true,
    val showPauseOverlay: Boolean = false,
    val showSubtitleOverlay: Boolean = false,
    val showAudioOverlay: Boolean = false,
    val showQualityOverlay: Boolean = false,
    val showSpeedOverlay: Boolean = false,
    val showSubtitleStylePanel: Boolean = false,
    // Streaming mode
    val isStreamingMode: Boolean = false,
    val isMagnet: Boolean = false,
    val currentSourceIndex: Int = 1,
    /** Per-source status shown in the sources panel: "loading" | "ok" | "failed". */
    val sourceStatus: Map<Int, String> = emptyMap(),
    val streamUrl: String? = null,
    val referer: String? = null,
    val animeOrigin: String? = null,
    val animeTracksJson: String? = null,
    val animeEmbedsJson: String? = null,
    val animeServer: String? = null,
    val animeEmbedUrl: String? = null,
    val showSourcesPanel: Boolean = false,
    val isSwitchingSource: Boolean = false,
    val animeEmbeds: List<com.playtorrio.tv.data.anime.AnimeEmbed>? = null,
    val currentAnimeServer: String? = null,
    val currentAnimeEmbedUrl: String? = null,
    /** True for direct IPTV (Xtream-Codes) streams — disables source fallback / picker. */
    val isIptv: Boolean = false,
    // ── Addon-origin sources (Stremio streams for the current item) ──
    /** True when playback originated from a Stremio addon — the Sources panel
     *  then lists the addon streams for this item instead of the built-in
     *  extractor sources (Xpass etc.), which are unrelated to addon content. */
    val isAddonOrigin: Boolean = false,
    val addonStreams: List<com.playtorrio.tv.data.stremio.StremioStream> = emptyList(),
    val isLoadingAddonStreams: Boolean = false,
    /** Key (url/infoHash) of the currently playing addon stream, for marking
     *  the active row in the panel. */
    val currentStreamPickKey: String? = null,
    // Episodes (for series)
    val episodes: List<com.playtorrio.tv.data.model.Episode> = emptyList(),
    val isLoadingEpisodes: Boolean = false,
    val showEpisodesPanel: Boolean = false,
    val nextEpisode: com.playtorrio.tv.data.model.Episode? = null,
    val isSwitchingEpisode: Boolean = false,
    /** Auto-play the next episode when the current one ends. Session mirror of
     *  AppPreferences.autoplayNext; toggleable from the player controls. */
    val autoplayNext: Boolean = true,
    // ── Up Next overlay + suggestions slideshow ──
    /** Suggestion shown as "Up Next" for movies (or when there's no next
     *  episode). Series use [nextEpisode] instead. */
    val upNextSuggestion: com.playtorrio.tv.data.playback.PlaybackQueue.Item? = null,
    val showUpNextOverlay: Boolean = false,
    /** Slideshow suggestions: sibling items from the browsed catalog/filter, or
     *  TMDB recommendations when no queue was captured. */
    val suggestions: List<com.playtorrio.tv.data.playback.PlaybackQueue.Item> = emptyList(),
    val showSuggestionsPanel: Boolean = false,
    val isLoadingSuggestions: Boolean = false,
    val suggestionsPage: Int = 1,
    val suggestionsHasMore: Boolean = true,
    // Episode source picker (when switching ep in non-streaming mode)
    val showEpisodeSourceOverlay: Boolean = false,
    val episodeOverlayKind: EpisodeOverlayKind = EpisodeOverlayKind.NONE,
    val pendingEpisode: com.playtorrio.tv.data.model.Episode? = null,
    val episodeOverlayTorrents: List<com.playtorrio.tv.data.torrent.TorrentResult> = emptyList(),
    val episodeOverlayStreams: List<com.playtorrio.tv.data.stremio.StremioStream> = emptyList(),
    val isLoadingEpisodeOverlay: Boolean = false,
    // Error
    val error: String? = null
)

enum class EpisodeOverlayKind { NONE, TORRENT, ADDON_STREAM }

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    var player: ExoPlayer? = null; private set
    // Up Next overlay bookkeeping.
    private var upNextHideJob: Job? = null
    private var upNextTriggered = false
    /** When autoplay advances to a torrent/addon episode, auto-pick the top
     *  source instead of waiting for the user to choose in the overlay. */
    private var pendingAutoPickEpisode = false
    /** Extra HTTP headers the current stream requires (Stremio
     *  behaviorHints.proxyHeaders) — merged LAST into the data-source headers
     *  so they override defaults, including User-Agent. */
    private var customStreamHeaders: Map<String, String> = emptyMap()
    private var currentStreamUrl: String? = null
    /** Track which stream URL we already auto-pinned highest quality for. */
    private var autoQualityAppliedForUrl: String? = null
    private val addedExternalSubConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
    private val addedExternalSubLabels = mutableMapOf<String, String>() // url -> label
    /** Original (un-shifted) external subtitle URL keyed by its proxy URL — needed
     *  to rebuild SubtitleConfigurations when the delay changes. */
    private val externalSubOriginalUrls = mutableMapOf<String, String>() // proxyUrl -> originalUrl
    private val externalSubMimes = mutableMapOf<String, String>() // originalUrl -> "srt"|"vtt"
    private var pendingSubtitleLabel: String? = null // auto-select after reload
    private var positionJob: Job? = null
    private var controlsHideJob: Job? = null
    private var statsJob: Job? = null
    private var seekOverlayHideJob: Job? = null
    private var streamRetryJob: Job? = null
    private var startupRetryCount: Int = 0
    private val maxStartupRetries: Int = 4

    // ── IPTV stall watchdog & tiered recovery ────────────────────────────────
    // Live IPTV streams frequently freeze without emitting onPlayerError —
    // the socket dies, the HLS chunk loader hangs, or the upstream just stops
    // serving segments. We poll player state every second and trigger staged
    // recovery when buffering exceeds a threshold or the playhead stops moving.
    private var iptvWatchdogJob: Job? = null
    private var iptvRetryAttempt: Int = 0
    private val iptvMaxRetries: Int = 8
    /** Wallclock timestamp (ms) when STATE_BUFFERING began. 0 = not buffering. */
    private var iptvBufferingSinceMs: Long = 0L
    /** Last observed currentPosition while playing. */
    private var iptvLastPositionMs: Long = -1L
    /** Wallclock timestamp (ms) when iptvLastPositionMs was last updated. */
    private var iptvLastPositionAt: Long = 0L
    /** Wallclock timestamp (ms) when the last successful progress was seen. Used by detector 3. */
    private var iptvLastHealthyAt: Long = 0L
    /** Wallclock timestamp (ms) when the current healthy-playback streak began. 0 = no active streak. */
    private var iptvHealthyStreakStartMs: Long = 0L
    /** Buffering longer than this with playWhenReady=true → stall. */
    private val iptvBufferingStallMs: Long = 6_000L
    /** Position not advancing for this long while READY+isPlaying → frozen. */
    private val iptvFrozenPositionMs: Long = 5_000L
    /** Reset retry counter after this much continuous healthy playback. */
    private val iptvHealthyResetMs: Long = 6_000L
    /** Unix-time (ms) of the playhead at the moment recovery started. Used to
     *  resume at the same wallclock position post-recovery so the user doesn't
     *  miss the seconds spent reconnecting. C.TIME_UNSET = no saved position. */
    private var iptvSavedUnixMs: Long = C.TIME_UNSET

    // ── VOD (movies/shows/anime/torrent) stall watchdog & recovery ────────────
    // Same idea as the IPTV watchdog but for on-demand playback. Scraper streams
    // and torrents frequently "play a bit then freeze" — the socket dies or the
    // chunk loader hangs WITHOUT ExoPlayer emitting onPlayerError, so nothing
    // kicks it back to life. We poll player state and recover, always resuming
    // at the frozen position (never restarting from 0) and, in streaming mode,
    // falling back to the next source once local recovery is exhausted.
    private var vodWatchdogJob: Job? = null
    private var vodRecoveryAttempt: Int = 0
    private val vodMaxRetries: Int = 5
    private var lastVodRecoveryAt: Long = 0L
    /** Wallclock (ms) when STATE_BUFFERING began. 0 = not buffering. */
    private var vodBufferingSinceMs: Long = 0L
    /** Last observed currentPosition while playing, and when it last changed. */
    private var vodLastPositionMs: Long = -1L
    private var vodLastPositionAt: Long = 0L
    /** Wallclock (ms) of the last healthy progress tick (used by detector 3). */
    private var vodLastHealthyAt: Long = 0L
    /** When the current healthy-playback streak began. 0 = no active streak. */
    private var vodHealthyStreakStartMs: Long = 0L
    /** Buffering longer than this (playWhenReady=true) → stall. Torrents get a
     *  longer grace period since slow-seed buffering is legitimate. */
    private val vodBufferingStallMs: Long = 20_000L
    private val vodMagnetBufferingStallMs: Long = 45_000L
    /** Position not advancing this long while READY+playing → frozen. */
    private val vodFrozenPositionMs: Long = 12_000L
    /** Reset the retry counter after this much continuous healthy playback. */
    private val vodHealthyResetMs: Long = 10_000L

    // ── Continue-watching context (set once by PlayerActivity from intent extras) ──
    private var currentMagnetUri: String? = null
    private var resumePosterUrl: String? = null
    private var resumeImdbId: String? = null
    private var resumeAddonId: String? = null
    private var resumeStremioType: String? = null
    private var resumeStremioId: String? = null
    /** Base SERIES meta id (no :season:episode suffix) — used to fetch the
     *  addon's episode list for the in-player Episodes panel. */
    private var resumeStremioSeriesId: String? = null
    private var resumeStreamPickKey: String? = null
    private var resumeStreamPickName: String? = null
    
    private var resumeAnimeId: String? = null
    private var resumeAnimeCategory: String? = null
    private var resumeFileIdx: Int? = null
    private var pendingSeekMs: Long? = null
    private var lastProgressSaveAt: Long = 0L
    /** Captured from createStreamingPlayer so episode-switching can reuse the same headers. */
    private var currentReferer: String = ""

    init {
        _uiState.update { it.copy(autoplayNext = AppPreferences.autoplayNext) }
    }

    fun toggleAutoplayNext() {
        val v = !_uiState.value.autoplayNext
        AppPreferences.autoplayNext = v
        _uiState.update { it.copy(autoplayNext = v) }
    }

    // ── Up Next overlay ──────────────────────────────────────────────────────

    /** Called ~each position tick. Shows the Up Next overlay once we pass the
     *  midpoint, then auto-hides it after the user-configured duration. */
    private fun maybeShowUpNext(pos: Long, dur: Long) {
        if (upNextTriggered) return
        if (!AppPreferences.upNextOverlayEnabled) return
        if (dur <= 0L || pos < dur / 2) return
        val s = _uiState.value
        if (s.isIptv || s.isSwitchingEpisode) return
        val hasNext = (!s.isMovie && s.nextEpisode != null) || s.upNextSuggestion != null
        if (!hasNext) return
        upNextTriggered = true
        _uiState.update { it.copy(showUpNextOverlay = true) }
        upNextHideJob?.cancel()
        upNextHideJob = viewModelScope.launch {
            delay(AppPreferences.upNextOverlaySec * 1000L)
            _uiState.update { it.copy(showUpNextOverlay = false) }
        }
    }

    fun dismissUpNextOverlay() {
        upNextHideJob?.cancel()
        _uiState.update { it.copy(showUpNextOverlay = false) }
    }

    /** Play whatever the Up Next card points at (next episode or a suggestion). */
    fun playUpNext() {
        val s = _uiState.value
        dismissUpNextOverlay()
        when {
            !s.isMovie && s.nextEpisode != null -> playNextEpisode()
            s.upNextSuggestion != null -> playSuggestion(s.upNextSuggestion!!)
        }
    }

    private fun resetUpNextForNewItem() {
        upNextTriggered = false
        upNextHideJob?.cancel()
        _uiState.update { it.copy(showUpNextOverlay = false) }
    }

    /** Fired when a VOD reaches STATE_ENDED. Auto-advances if enabled. */
    private fun onVodEnded() {
        val s = _uiState.value
        if (!s.autoplayNext) return
        when {
            !s.isMovie && s.nextEpisode != null -> playNextEpisodeAuto()
            s.upNextSuggestion != null -> playSuggestion(s.upNextSuggestion!!)
        }
    }

    fun playNextEpisodeAuto() {
        pendingAutoPickEpisode = true
        playNextEpisode()
    }

    // ── Suggestions (TMDB "recommendations") ─────────────────────────────────

    private fun currentQueueIdentity(): String {
        val s = _uiState.value
        return when {
            resumeAnimeId != null -> "anime:$resumeAnimeId"
            currentMagnetUri != null -> "torrent:$currentMagnetUri"
            resumeStremioId != null -> "addon:$resumeStremioId"
            s.tmdbId > 0 -> "tmdb:${s.tmdbId}"
            else -> ""
        }
    }

    private suspend fun fetchTmdbRecs(
        tmdbId: Int, isMovie: Boolean, page: Int
    ): Pair<List<PlaybackQueue.Item>, Boolean> = runCatching {
        val resp = if (isMovie)
            TmdbClient.api.getMovieRecommendations(tmdbId, TmdbClient.API_KEY, page)
        else
            TmdbClient.api.getTvRecommendations(tmdbId, TmdbClient.API_KEY, page)
        val items = resp.results.mapNotNull { m ->
            val t = m.title ?: m.name ?: return@mapNotNull null
            val mv = m.mediaType?.let { it == "movie" } ?: (m.title != null)
            PlaybackQueue.Item(
                kind = PlaybackQueue.Kind.TMDB,
                title = t,
                thumbnailUrl = m.posterUrl,
                tmdbId = m.id,
                isMovie = mv,
            )
        }.filter { it.tmdbId != tmdbId }
        items to (page < resp.totalPages)
    }.getOrDefault(emptyList<PlaybackQueue.Item>() to false)

    /** Populate the slideshow once. Prefers the sibling queue captured from the
     *  catalog/filter the user was browsing (works for addon/torrent/anime with
     *  no TMDB id); falls back to TMDB recommendations otherwise. */
    private fun maybeLoadSuggestions() {
        val s = _uiState.value
        if (s.suggestions.isNotEmpty()) return

        // 1. Sibling queue from the browsed catalog/filter — only when the item
        //    we're actually playing is one of the captured siblings (otherwise the
        //    queue is stale from an earlier browse and we ignore it).
        val queue = PlaybackQueue.items
        val curId = currentQueueIdentity()
        if (queue.isNotEmpty() && curId.isNotBlank() && queue.any { it.identity == curId }) {
            val siblings = queue.filter { it.identity != curId }
            if (siblings.isNotEmpty()) {
                val idx = queue.indexOfFirst { it.identity == curId }
                val nextSibling = if (idx >= 0) queue.getOrNull(idx + 1) else null
                _uiState.update {
                    it.copy(
                        suggestions = siblings,
                        suggestionsHasMore = false,
                        upNextSuggestion = if (!it.isMovie && it.nextEpisode != null) it.upNextSuggestion
                            else (nextSibling ?: siblings.firstOrNull())
                    )
                }
                return
            }
        }

        // 2. TMDB recommendations fallback.
        val tmdb = s.tmdbId.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            val (items, hasMore) = fetchTmdbRecs(tmdb, s.isMovie, 1)
            _uiState.update {
                it.copy(
                    suggestions = items,
                    suggestionsPage = 1,
                    suggestionsHasMore = hasMore,
                    upNextSuggestion = if (!it.isMovie && it.nextEpisode != null) it.upNextSuggestion
                        else items.firstOrNull()
                )
            }
        }
    }

    fun openSuggestionsPanel() {
        _uiState.update { it.copy(showSuggestionsPanel = true, showControls = false, showUpNextOverlay = false) }
        maybeLoadSuggestions()
    }

    fun dismissSuggestionsPanel() {
        _uiState.update { it.copy(showSuggestionsPanel = false) }
        showControls()
    }

    fun loadMoreSuggestions() {
        // Only the TMDB-recommendation fallback paginates; the sibling queue is finite.
        if (PlaybackQueue.items.isNotEmpty()) return
        val s = _uiState.value
        val tmdb = s.tmdbId.takeIf { it > 0 } ?: return
        if (s.isLoadingSuggestions || !s.suggestionsHasMore) return
        _uiState.update { it.copy(isLoadingSuggestions = true) }
        viewModelScope.launch {
            val next = s.suggestionsPage + 1
            val (items, hasMore) = fetchTmdbRecs(tmdb, s.isMovie, next)
            val existing = _uiState.value.suggestions.mapTo(HashSet()) { it.tmdbId }
            _uiState.update {
                it.copy(
                    suggestions = it.suggestions + items.filter { x -> x.tmdbId !in existing },
                    suggestionsPage = if (items.isEmpty()) it.suggestionsPage else next,
                    suggestionsHasMore = hasMore && items.isNotEmpty(),
                    isLoadingSuggestions = false
                )
            }
        }
    }

    /** Play a slideshow / Up-Next item in-place, dispatching by its source kind. */
    fun playSuggestion(item: PlaybackQueue.Item) {
        when (item.kind) {
            PlaybackQueue.Kind.TMDB ->
                playTmdbTitle(item.tmdbId, item.isMovie, item.title, item.thumbnailUrl)
            PlaybackQueue.Kind.TORRENT ->
                item.magnet?.let { playMagnetItem(it, item.title, item.thumbnailUrl, item.isMovie) }
            PlaybackQueue.Kind.ADDON ->
                item.stremioId?.let {
                    playAddonItem(item.addonId, item.stremioType ?: "movie", it, item.title, item.thumbnailUrl)
                }
            PlaybackQueue.Kind.ANIME ->
                item.animeId?.toIntOrNull()?.let { playAnimeItem(it, item.title, item.thumbnailUrl) }
        }
    }

    /** Common state reset for an in-place swap to a brand-new title. */
    private fun beginInPlaceSwap(title: String, poster: String?, isMovie: Boolean, tmdbId: Int, status: String) {
        flushProgressInternal()
        resetUpNextForNewItem()
        customStreamHeaders = emptyMap()
        currentMagnetUri = null
        resumeAddonId = null
        resumeStremioType = null
        resumeStremioId = null
        resumeStremioSeriesId = null
        resumeStreamPickKey = null
        resumeStreamPickName = null
        resumeImdbId = null
        resumeFileIdx = null
        resumeAnimeId = null
        resumeAnimeCategory = null
        resumePosterUrl = poster
        pendingSeekMs = null
        _uiState.update {
            it.copy(
                showSuggestionsPanel = false,
                showUpNextOverlay = false,
                isSwitchingEpisode = true,
                isConnecting = true,
                connectionStatus = status,
                title = title,
                isMovie = isMovie,
                tmdbId = tmdbId,
                seasonNumber = if (isMovie) null else 1,
                episodeNumber = if (isMovie) null else 1,
                episodeTitle = null,
                duration = 0L,
                currentPosition = 0L,
                episodes = emptyList(),
                nextEpisode = null,
                upNextSuggestion = null,
                // Cleared so maybeLoadSuggestions() repopulates (queue persists in the holder).
                suggestions = emptyList(),
                suggestionsPage = 1,
                suggestionsHasMore = true,
                activeSkipSegment = null,
                skipSegments = emptyList(),
                externalSubtitles = emptyList(),
                animeEmbeds = null,
                animeTracksJson = null,
                animeEmbedsJson = null,
                isAddonOrigin = false,
                addonStreams = emptyList(),
                isLoadingAddonStreams = false,
                currentStreamPickKey = null,
            )
        }
    }

    private suspend fun teardownForSwap() {
        val oldHash = _uiState.value.torrentHash
        if (!oldHash.isNullOrBlank()) {
            runCatching { com.playtorrio.tv.data.torrent.TorrServerService.removeTorrent(oldHash) }
        }
        statsJob?.cancel()
    }

    private fun playTmdbTitle(tmdbId: Int, isMovie: Boolean, title: String, poster: String?) {
        if (_uiState.value.isSwitchingEpisode) return
        beginInPlaceSwap(title, poster, isMovie, tmdbId, "Loading \"$title\"…")
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                teardownForSwap()
                val sourceIdx = _uiState.value.currentSourceIndex
                val result = StreamExtractorService.extract(
                    context = context, sourceIdx = sourceIdx, tmdbId = tmdbId,
                    season = if (isMovie) null else 1, episode = if (isMovie) null else 1,
                    timeoutMs = AppPreferences.streamingExtractTimeoutSec * 1000L,
                )
                if (result == null) {
                    _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = "Couldn't find a source for \"$title\"") }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    addedExternalSubConfigs.clear(); addedExternalSubLabels.clear()
                    player?.release(); player = null
                    _uiState.update { it.copy(isSwitchingEpisode = false, isStreamingMode = true, isMagnet = false, torrentHash = null) }
                    createStreamingPlayer(result.url, result.referer)
                }
                if (!isMovie) runCatching { loadEpisodesForCurrentSeries() }
                launch {
                    val segments = SkipSegmentService.fetchSegments(tmdbId, isMovie, if (isMovie) null else 1, if (isMovie) null else 1)
                    _uiState.update { it.copy(skipSegments = segments) }
                }
                launch {
                    val subs = SubtitleService.fetchSubtitles(
                        tmdbId = tmdbId, season = if (isMovie) null else 1, episode = if (isMovie) null else 1,
                        title = title, year = null,
                    )
                    _uiState.update { it.copy(externalSubtitles = subs) }
                    updateSubtitleTrackList()
                }
                maybeLoadSuggestions()
            } catch (e: Exception) {
                Log.e(TAG, "playTmdbTitle failed", e)
                _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message) }
            }
        }
    }

    /** Torrent-hub / stored-magnet item → resolve via TorrServer (or debrid) and swap. */
    private fun playMagnetItem(magnet: String, title: String, poster: String?, isMovie: Boolean) {
        if (_uiState.value.isSwitchingEpisode) return
        beginInPlaceSwap(title, poster, isMovie, tmdbId = -1, status = "Loading \"$title\"…")
        currentMagnetUri = magnet
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                teardownForSwap()
                if (AppPreferences.debridEnabled) {
                    val url = DebridResolver.resolve(magnet, isMovie, null, null)
                        ?: throw IllegalStateException("Debrid could not resolve this torrent")
                    withContext(Dispatchers.Main) {
                        player?.release(); player = null
                        _uiState.update { it.copy(isSwitchingEpisode = false, isStreamingMode = false, isMagnet = true, torrentHash = null) }
                        createPlayer(url)
                    }
                } else {
                    com.playtorrio.tv.data.torrent.TorrServerService.ensureInitialized(context)
                    val result = com.playtorrio.tv.data.torrent.TorrServerService.startStreaming(
                        context = context, magnetUri = magnet, seasonNumber = null, episodeNumber = null
                    )
                    withContext(Dispatchers.Main) {
                        player?.release(); player = null
                        _uiState.update { it.copy(isSwitchingEpisode = false, isStreamingMode = false, isMagnet = true, torrentHash = result.hash) }
                        startStatsPoller(result.hash)
                        createPlayer(result.url)
                    }
                }
                maybeLoadSuggestions()
            } catch (e: Exception) {
                Log.e(TAG, "playMagnetItem failed", e)
                _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message ?: "Couldn't play \"$title\"") }
            }
        }
    }

    /** Stremio addon catalog item → getStreams → route → swap in-place. */
    private fun playAddonItem(addonId: String?, type: String, stremioId: String, title: String, poster: String?) {
        if (_uiState.value.isSwitchingEpisode) return
        val isMovie = type == "movie"
        beginInPlaceSwap(title, poster, isMovie, tmdbId = 0, status = "Loading \"$title\"…")
        resumeAddonId = addonId?.takeIf { it.isNotBlank() && it != "_auto_" }
        resumeStremioType = type
        resumeStremioId = stremioId
        resumeStremioSeriesId = stremioId
        _uiState.update { it.copy(isAddonOrigin = true) }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                teardownForSwap()
                val addons = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
                val candidateTypes = listOf(type, "movie", "series", "channel", "tv").distinct()
                var streams: List<com.playtorrio.tv.data.stremio.StremioStream> = emptyList()
                for (t in candidateTypes) {
                    streams = com.playtorrio.tv.data.stremio.StremioService.getStreams(addons, t, stremioId, resumeAddonId)
                    if (streams.isNotEmpty()) break
                }
                val stream = streams.firstOrNull()
                if (stream == null) {
                    _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = "No stream for \"$title\"") }
                    return@launch
                }
                resumeStreamPickKey = stream.url ?: stream.infoHash
                resumeStreamPickName = stream.name ?: stream.title
                when (val route = com.playtorrio.tv.data.stremio.StremioService.routeStream(stream)) {
                    is com.playtorrio.tv.data.stremio.StreamRoute.DirectUrl -> {
                        val referer = route.headers?.get("Referer") ?: route.headers?.get("referer") ?: ""
                        customStreamHeaders = route.headers ?: emptyMap()
                        withContext(Dispatchers.Main) {
                            addedExternalSubConfigs.clear(); addedExternalSubLabels.clear()
                            player?.release(); player = null
                            _uiState.update { it.copy(isSwitchingEpisode = false, isStreamingMode = true, isMagnet = false, torrentHash = null) }
                            if (referer.isNotBlank()) createStreamingPlayer(route.url, referer) else createPlayer(route.url)
                        }
                    }
                    is com.playtorrio.tv.data.stremio.StreamRoute.Torrent -> {
                        currentMagnetUri = route.magnet
                        resumeFileIdx = route.fileIdx
                        com.playtorrio.tv.data.torrent.TorrServerService.ensureInitialized(context)
                        val result = com.playtorrio.tv.data.torrent.TorrServerService.startStreaming(
                            context = context, magnetUri = route.magnet, seasonNumber = null, episodeNumber = null, fileIdx = route.fileIdx
                        )
                        withContext(Dispatchers.Main) {
                            player?.release(); player = null
                            _uiState.update { it.copy(isSwitchingEpisode = false, isStreamingMode = false, isMagnet = true, torrentHash = result.hash) }
                            startStatsPoller(result.hash)
                            createPlayer(result.url)
                        }
                    }
                    else -> {
                        _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = "Unsupported stream for \"$title\"") }
                        return@launch
                    }
                }
                maybeLoadSuggestions()
            } catch (e: Exception) {
                Log.e(TAG, "playAddonItem failed", e)
                _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message ?: "Couldn't play \"$title\"") }
            }
        }
    }

    private data class AnimeResolved(
        val stream: com.playtorrio.tv.data.anime.AnimeStreamResult,
        val winningEmbed: com.playtorrio.tv.data.anime.AnimeEmbed?,
        val allEmbeds: List<com.playtorrio.tv.data.anime.AnimeEmbed>,
    )

    /** Resolve a playable stream for an anime episode by racing the available
     *  sources (first success wins). Shared by first-play and episode-switch. */
    private suspend fun resolveAnimeEpisode(anilistId: Int, episode: Int, category: String): AnimeResolved? {
        val svc = com.playtorrio.tv.data.anime.AnimeService
        val anime = runCatching { svc.getDetails(anilistId) }.getOrNull() ?: return null
        val series = runCatching { svc.resolveAnikoto(anime) }.getOrNull()
        val titles = listOf(anime.titleEnglish, anime.titleRomaji, anime.titleNative)
            .filter { it.isNotBlank() }.distinct()
        val allEmbeds = svc.buildAllEmbeds(
            anilistId = anilistId, episode = episode, series = series,
            category = null, animeTitles = titles, isAdult = anime.isAdult
        )
        val target = allEmbeds.filter { it.category == category }.ifEmpty { allEmbeds }
        for (embed in target) {
            val r = runCatching { svc.extractDirect(embed) }.getOrNull()
            if (r != null) return AnimeResolved(r, embed, allEmbeds)
        }
        return null
    }

    private fun animeTracksJsonOf(r: com.playtorrio.tv.data.anime.AnimeStreamResult): String =
        org.json.JSONArray().apply {
            r.tracks.forEach { put(org.json.JSONObject().put("url", it.url).put("label", it.label).put("isDefault", it.isDefault)) }
        }.toString()

    private fun animeEmbedsJsonOf(embeds: List<com.playtorrio.tv.data.anime.AnimeEmbed>): String =
        org.json.JSONArray().apply {
            embeds.forEach { put(org.json.JSONObject().put("url", it.url).put("server", it.server).put("category", it.category).put("label", it.label)) }
        }.toString()

    /** Anime series item → resolve first episode → swap in-place. */
    private fun playAnimeItem(anilistId: Int, title: String, poster: String?) {
        if (_uiState.value.isSwitchingEpisode) return
        beginInPlaceSwap(title, poster, isMovie = false, tmdbId = 0, status = "Loading \"$title\"…")
        resumeAnimeId = anilistId.toString()
        val category = "sub"
        resumeAnimeCategory = category
        viewModelScope.launch {
            try {
                teardownForSwap()
                val res = resolveAnimeEpisode(anilistId, 1, category)
                if (res == null) {
                    _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = "No source for \"$title\"") }
                    return@launch
                }
                val w = res.stream
                val tracksJson = animeTracksJsonOf(w)
                val embedsJson = animeEmbedsJsonOf(res.allEmbeds)
                withContext(Dispatchers.Main) {
                    addedExternalSubConfigs.clear(); addedExternalSubLabels.clear()
                    player?.release(); player = null
                    _uiState.update {
                        it.copy(
                            isSwitchingEpisode = false, isStreamingMode = true, isMagnet = false, torrentHash = null,
                            episodeTitle = "Episode 1", seasonNumber = 1, episodeNumber = 1,
                            animeOrigin = w.origin, animeTracksJson = tracksJson, animeEmbedsJson = embedsJson,
                            animeServer = res.winningEmbed?.server, animeEmbedUrl = res.winningEmbed?.url,
                        )
                    }
                    createStreamingPlayer(w.url, w.referer, animeTracksJson = tracksJson, animeOrigin = w.origin)
                }
                runCatching { loadEpisodesForCurrentSeries() }
                maybeLoadSuggestions()
            } catch (e: Exception) {
                Log.e(TAG, "playAnimeItem failed", e)
                _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message ?: "Couldn't play \"$title\"") }
            }
        }
    }

    /** Switch to another episode of the CURRENT anime (keeps the episode list). */
    private fun switchToAnimeEpisode(epNum: Int) {
        val anilistId = resumeAnimeId?.toIntOrNull() ?: return
        val category = resumeAnimeCategory ?: "sub"
        pendingAutoPickEpisode = false
        flushProgressInternal()
        resetUpNextForNewItem()
        _uiState.update {
            it.copy(
                showEpisodesPanel = false,
                showEpisodeSourceOverlay = false,
                isSwitchingEpisode = true,
                isConnecting = true,
                connectionStatus = "Loading episode $epNum…",
                seasonNumber = 1,
                episodeNumber = epNum,
                episodeTitle = "Episode $epNum",
                duration = 0L,
                currentPosition = 0L,
                activeSkipSegment = null,
                skipSegments = emptyList(),
                externalSubtitles = emptyList(),
                nextEpisode = null,
            )
        }
        viewModelScope.launch {
            try {
                teardownForSwap()
                val res = resolveAnimeEpisode(anilistId, epNum, category)
                if (res == null) {
                    _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = "No source for episode $epNum") }
                    return@launch
                }
                val w = res.stream
                val tracksJson = animeTracksJsonOf(w)
                val embedsJson = animeEmbedsJsonOf(res.allEmbeds)
                withContext(Dispatchers.Main) {
                    addedExternalSubConfigs.clear(); addedExternalSubLabels.clear()
                    player?.release(); player = null
                    _uiState.update {
                        it.copy(
                            isSwitchingEpisode = false, isStreamingMode = true, isMagnet = false, torrentHash = null,
                            animeOrigin = w.origin, animeTracksJson = tracksJson, animeEmbedsJson = embedsJson,
                            animeServer = res.winningEmbed?.server, animeEmbedUrl = res.winningEmbed?.url,
                        )
                    }
                    createStreamingPlayer(w.url, w.referer, animeTracksJson = tracksJson, animeOrigin = w.origin)
                }
                computeNextEpisode()
            } catch (e: Exception) {
                Log.e(TAG, "switchToAnimeEpisode failed", e)
                _uiState.update { it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message ?: "Couldn't load episode $epNum") }
            }
        }
    }

    fun setResumeContext(
        posterUrl: String?,
        imdbId: String?,
        addonId: String?,
        stremioType: String?,
        stremioId: String?,
        streamPickKey: String?,
        streamPickName: String?,
        resumePositionMs: Long?,
        fileIdx: Int?,
        stremioSeriesId: String? = null,
    ) {
        resumePosterUrl = posterUrl
        resumeImdbId = imdbId?.takeIf { it.isNotBlank() }
        resumeAddonId = addonId?.takeIf { it.isNotBlank() }
        resumeStremioType = stremioType?.takeIf { it.isNotBlank() }
        resumeStremioId = stremioId?.takeIf { it.isNotBlank() }
        resumeStremioSeriesId = stremioSeriesId?.takeIf { it.isNotBlank() }
        resumeStreamPickKey = streamPickKey?.takeIf { it.isNotBlank() }
        resumeStreamPickName = streamPickName?.takeIf { it.isNotBlank() }
        resumeFileIdx = fileIdx
        pendingSeekMs = resumePositionMs
        _uiState.update {
            it.copy(
                isAddonOrigin = resumeStremioId != null,
                currentStreamPickKey = resumeStreamPickKey,
            )
        }
    }

    private fun resetStartupRetryState() {
        startupRetryCount = 0
        streamRetryJob?.cancel()
        streamRetryJob = null
    }

    /** Source priority order matching StreamingSplash; reads user setting each time. */
    private val sourcePriorityOrder: List<Int>
        get() = AppPreferences.streamingSourceOrder
    private val orderedSourceIndices: List<Int>
        get() {
            val priority = sourcePriorityOrder
            return buildList {
                priority.forEach { idx ->
                    StreamExtractorService.SOURCES.find { it.index == idx }?.let { add(idx) }
                }
                StreamExtractorService.SOURCES
                    .filterNot { src -> priority.contains(src.index) }
                    .forEach { add(it.index) }
            }
        }
    /** Track sources we already failed on this session so we don't cycle back. */
    private val failedSourceIndices = mutableSetOf<Int>()

    /** True until the user manually picks a source in the panel. While true we
     *  auto-advance through sources on failure; once the user chooses, we stop
     *  overriding their choice and just keep (re)trying the picked source. */
    private var autoAdvanceSources: Boolean = true

    private fun setSourceStatus(idx: Int, status: String) {
        _uiState.update { it.copy(sourceStatus = it.sourceStatus + (idx to status)) }
    }

    private fun parseHeadersJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(json)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.getString(k)) }
            }
        }.getOrDefault(emptyMap())
    }

    /** An exception thrown inside a Player.Listener callback crashes the whole
     *  app — which the user experiences as the player "randomly exiting" back to
     *  the detail page. Every listener body goes through this guard. */
    private inline fun guarded(tag: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Log.e(TAG, "Guarded listener crash in $tag (ignored)", e)
        }
    }

    private fun tryNextSource(reason: String) {
        val state = _uiState.value

        // Addon-origin streams (the user picked a specific addon stream — even
        // for a TMDB title) must never be silently replaced by extractor sources
        // like Xpass. Reconnect the SAME stream instead; the user can switch via
        // the addon-aware sources panel.
        val addonOrigin = state.isStreamingMode && state.animeEmbeds == null && !state.isIptv &&
            (state.isAddonOrigin || state.tmdbId <= 0)

        // Respect a manual source choice: don't jump to another source. Mark it
        // and keep reconnecting the SAME source (resilient, never gives up).
        if ((addonOrigin || !autoAdvanceSources) && state.isStreamingMode && state.animeEmbeds == null) {
            setSourceStatus(state.currentSourceIndex, "loading")
            _uiState.update {
                it.copy(isReconnecting = true, reconnectStatus = "Reconnecting…", error = null)
            }
            currentStreamUrl?.let { url ->
                streamRetryJob?.cancel()
                streamRetryJob = viewModelScope.launch {
                    delay(3_000)
                    val exo = player ?: return@launch
                    try {
                        exo.stop(); exo.clearMediaItems()
                        exo.setMediaItem(MediaItem.fromUri(url)); exo.prepare(); exo.playWhenReady = true
                    } catch (_: Exception) {}
                }
            }
            return
        }
        // IPTV streams have no fallback sources — surface the error directly.
        if (state.isIptv) {
            _uiState.update {
                it.copy(
                    isConnecting = false,
                    error = "Stream failed: $reason",
                )
            }
            return
        }

        if (state.animeEmbeds != null) {
            val list = state.animeEmbeds
            val currentIdx = list.indexOfFirst { it.url == state.currentAnimeEmbedUrl }
            val nextIdx = currentIdx + 1
            if (nextIdx in list.indices) {
                val nextEmbed = list[nextIdx]
                Log.i(TAG, "Stream failed ($reason), switching to anime embed ${nextEmbed.server}")
                _uiState.update {
                    it.copy(
                        isConnecting = true,
                        connectionStatus = "Source failed, trying ${nextEmbed.label}."
                    )
                }
                switchAnimeSource(nextEmbed)
            } else {
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        error = "All anime sources failed."
                    )
                }
            }
            return
        }
        failedSourceIndices.add(state.currentSourceIndex)
        setSourceStatus(state.currentSourceIndex, "failed")
        Log.i(TAG, "tryNextSource: failed=${state.currentSourceIndex}, failedSet=$failedSourceIndices, tmdbId=${state.tmdbId} s=${state.seasonNumber} e=${state.episodeNumber}")

        val nextIdx = orderedSourceIndices.firstOrNull { it !in failedSourceIndices }
        if (nextIdx != null) {
            val sourceName = StreamExtractorService.SOURCES.find { it.index == nextIdx }?.name ?: "source"
            Log.i(TAG, "Stream failed ($reason), switching to $sourceName")
            setSourceStatus(nextIdx, "loading")
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectionStatus = "Source failed, trying $sourceName…"
                )
            }
            switchToSource(nextIdx)
        } else {
            // Never give up — the user exits deliberately. Clear the failed set
            // and start the whole source list over after a short pause, keeping
            // a "Reconnecting…" indicator up. Cancelled cleanly on player teardown.
            Log.w(TAG, "All sources tried ($reason) — cycling from the top")
            failedSourceIndices.clear()
            _uiState.update {
                it.copy(isConnecting = true, isReconnecting = true,
                    reconnectStatus = "Reconnecting…", error = null)
            }
            streamRetryJob?.cancel()
            streamRetryJob = viewModelScope.launch {
                delay(4_000)
                orderedSourceIndices.firstOrNull()?.let { switchToSource(it) }
            }
        }
    }

    private fun scheduleStreamStartupRetry(exo: ExoPlayer, error: PlaybackException) {
        val url = currentStreamUrl ?: return
        if (streamRetryJob?.isActive == true) return

        val resumePos = exo.currentPosition.coerceAtLeast(0L)
        val midPlayback = resumePos > 10_000L
        val streaming = _uiState.value.isStreamingMode

        // At STARTUP in streaming mode the source produced nothing playable —
        // switch to another source immediately. But once playback has begun,
        // an error is almost always a transient network blip: ride it out by
        // re-preparing the SAME source at the current position (below) instead
        // of abandoning a working stream. Only after repeated failures do we
        // fall back to another source.
        if (streaming && !midPlayback) {
            tryNextSource(error.errorCodeName)
            return
        }

        if (startupRetryCount >= maxStartupRetries) {
            if (streaming) {
                if (resumePos > 5_000L) pendingSeekMs = resumePos
                resetStartupRetryState()
                tryNextSource(error.errorCodeName)
                return
            }
            // Torrent: never give up — the user exits deliberately. Reset the
            // counter so we keep re-preparing the same stream at a steady pace.
            Log.w(TAG, "Torrent retries exhausted — continuing to retry (no give-up)")
            startupRetryCount = 0
        }

        val attempt = startupRetryCount + 1
        val delayMs = (attempt * 2_000L).coerceAtMost(8_000L)
        _uiState.update {
            it.copy(isReconnecting = true, reconnectStatus = "Reconnecting…")
        }

        streamRetryJob = viewModelScope.launch {
            delay(delayMs)
            if (player !== exo) return@launch

            startupRetryCount = attempt
            Log.w(TAG, "Re-preparing same stream (attempt $attempt) due to ${error.errorCodeName}")

            // Preserve position so we resume where we stalled, not from 0.
            if (resumePos > 5_000L) pendingSeekMs = resumePos

            try {
                exo.stop()
                exo.clearMediaItems()
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                exo.playWhenReady = true
            } catch (e: Exception) {
                Log.w(TAG, "retry re-prepare failed: ${e.message}")
            }
        }
    }

    fun initPlayer(
        magnetUri: String,
        title: String,
        logoUrl: String?,
        backdropUrl: String?,
        year: String?,
        rating: String?,
        overview: String?,
        isMovie: Boolean,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        tmdbId: Int
    ) {
        if (player != null) return

        currentMagnetUri = magnetUri

        _uiState.update {
            it.copy(
                title = title,
                logoUrl = logoUrl,
                backdropUrl = backdropUrl,
                year = year,
                rating = rating,
                overview = overview,
                isMovie = isMovie,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                tmdbId = tmdbId,
                isConnecting = true,
                connectionStatus = "Starting TorrServer…"
            )
        }

        val context = getApplication<Application>()

        // Do TorrServer setup → get stream URL → create player
        viewModelScope.launch {
            try {
                if (AppPreferences.debridEnabled) {
                    _uiState.update { it.copy(connectionStatus = "Resolving via debrid…") }
                    val debridUrl = DebridResolver.resolve(magnetUri, isMovie, seasonNumber, episodeNumber)
                        ?: throw IllegalStateException(
                            "Debrid is enabled, but this torrent is not cached or could not be resolved."
                        )

                    _uiState.update {
                        it.copy(
                            connectionStatus = "Starting debrid stream…",
                            isConnecting = false
                        )
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        createPlayer(debridUrl)
                    }
                } else {
                    _uiState.update { it.copy(connectionStatus = "Connecting to TorrServer…") }
                    TorrServerService.ensureInitialized(context)

                    _uiState.update { it.copy(connectionStatus = "Adding torrent…") }
                    val result = TorrServerService.startStreaming(
                        context = context,
                        magnetUri = magnetUri,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        onStatus = { msg -> _uiState.update { it.copy(connectionStatus = msg) } }
                    )

                    _uiState.update {
                        it.copy(
                            torrentHash = result.hash,
                            connectionStatus = "Starting playback…"
                        )
                    }

                    // Start stats poller only for TorrServer mode
                    startStatsPoller(result.hash)

                    // Create player on main thread
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        createPlayer(result.url)
                    }
                }

                // Fetch external subtitles in background
                launch {
                    val subs = SubtitleService.fetchSubtitles(
                        tmdbId = tmdbId,
                        season = seasonNumber,
                        episode = episodeNumber,
                        title = _uiState.value.title.takeIf { it.isNotBlank() },
                        year = _uiState.value.year?.toIntOrNull(),
                    )
                    _uiState.update { it.copy(externalSubtitles = subs) }
                    updateSubtitleTrackList()

                    // Also fetch from Stremio subtitle addons
                    fetchStremioSubtitles(tmdbId, isMovie, seasonNumber, episodeNumber)
                }

                // Fetch skip segments in background
                launch {
                    val segments = SkipSegmentService.fetchSegments(tmdbId, isMovie, seasonNumber, episodeNumber)
                    _uiState.update { it.copy(skipSegments = segments) }
                    Log.i(TAG, "Skip segments loaded: ${segments.size}")
                }

                // Eagerly load season for series so Next Episode button can appear.
                if (!isMovie && seasonNumber != null) {
                    launch { loadEpisodesForCurrentSeries() }
                }

                // Load "more like this" suggestions for Up Next + the slideshow.
                maybeLoadSuggestions()
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Stream setup failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        error = e.message ?: "Failed to start stream"
                    )
                }
            }
        }
    }

    private fun createPlayer(streamUrl: String) {
        val context = getApplication<Application>()
        resetStartupRetryState()

        // New stream → drop any in-overlay subtitle from the previous one.
        _uiState.update {
            it.copy(
                customSubtitleCues = emptyList(),
                customSubtitleLabel = null,
                customSubtitleSourceUrl = null,
                customSubtitleLoading = false,
            )
        }

        // Custom buffer for torrent streams: bigger buffer = fewer stalls
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // min buffer before playback starts
                120_000,  // max buffer to keep loaded
                2_500,    // playback start threshold
                5_000     // rebuffer threshold
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Use FFmpeg decoders as fallback for unsupported audio codecs (AC3, EAC3, DTS, TrueHD, etc.)
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        // Patient error policy: a slow torrent (peers dropped, cache draining)
        // should keep retrying and re-buffer rather than error out and exit.
        val torrentErrorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 12
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long {
                val attempt = loadErrorInfo.errorCount.coerceAtLeast(1)
                if (attempt > 12) return androidx.media3.common.C.TIME_UNSET
                return (1_500L * attempt).coerceAtMost(10_000L)
            }
        }
        val msFactory = DefaultMediaSourceFactory(context)
            .setLoadErrorHandlingPolicy(torrentErrorPolicy)
        currentStreamUrl = streamUrl
        autoQualityAppliedForUrl = null

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(msFactory)
            .setLoadControl(loadControl)
            .build()
        player = exo

        // Set audio attributes for proper audio routing on Android TV
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exo.setAudioAttributes(audioAttrs, false)
        exo.volume = 1.0f
        exo.setPlaybackSpeed(_uiState.value.playbackSpeed)

        Log.d(TAG, "Creating player with stream URL: $streamUrl")
        val mediaItem = MediaItem.fromUri(streamUrl)
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = guarded("createPlayer.stateChanged") {
                _uiState.update {
                    it.copy(
                        isBuffering = state == Player.STATE_BUFFERING,
                        isPlaying = exo.isPlaying,
                        isConnecting = if (state == Player.STATE_READY) false else it.isConnecting
                    )
                }
                if (state == Player.STATE_READY) {
                    resetStartupRetryState()
                    _uiState.update {
                        it.copy(
                            duration = exo.duration.coerceAtLeast(0),
                            isConnecting = false,
                            isReconnecting = false,
                            reconnectStatus = ""
                        )
                    }
                    Log.d(TAG, "Player ready. Volume: ${exo.volume}, AudioFormat: ${exo.audioFormat}")
                    updateTracks()
                    autoSelectPendingSubtitle()
                }
                if (state == Player.STATE_ENDED) onVodEnded()
            }

            override fun onIsPlayingChanged(playing: Boolean) = guarded("createPlayer.isPlaying") {
                _uiState.update { it.copy(isPlaying = playing) }
                if (!playing && exo.playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) = guarded("createPlayer.tracks") {
                updateTracks()
            }

            override fun onPlayerError(error: PlaybackException) = guarded("createPlayer.error") {
                Log.w(TAG, "Playback error (createPlayer): ${error.errorCodeName}", error)
                scheduleStreamStartupRetry(exo, error)
            }
        })

        startPositionUpdater()
        startVodWatchdog(exo)
        scheduleControlsHide()
    }

    private fun updateTracks() {
        val exo = player ?: return
        val tracks = exo.currentTracks

        // Audio tracks
        val audioTracks = mutableListOf<AudioTrackInfo>()
        var audioIdx = 0
        for ((groupIdx, group) in tracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                audioTracks.add(AudioTrackInfo(
                    index = audioIdx++,
                    groupIndex = groupIdx,
                    trackIndex = i,
                    label = format.label,
                    language = format.language,
                    codec = format.codecs,
                    channelCount = if (format.channelCount > 0) format.channelCount else null,
                    sampleRate = if (format.sampleRate > 0) format.sampleRate else null,
                    isSelected = group.isTrackSelected(i)
                ))
            }
        }
        Log.d(TAG, "Found ${audioTracks.size} audio tracks")

        // Video tracks (HLS variants, DASH renditions, etc.)
        val videoTracks = mutableListOf<VideoTrackInfo>()
        for ((groupIdx, group) in tracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                videoTracks.add(VideoTrackInfo(
                    groupIndex = groupIdx,
                    trackIndex = i,
                    width = if (format.width > 0) format.width else null,
                    height = if (format.height > 0) format.height else null,
                    bitrate = if (format.bitrate > 0) format.bitrate else null,
                    frameRate = if (format.frameRate > 0f) format.frameRate else null,
                    codec = format.codecs,
                    isSelected = group.isTrackSelected(i),
                ))
            }
        }
        // Sort highest-quality first for the picker.
        val sortedVideo = videoTracks.sortedWith(
            compareByDescending<VideoTrackInfo> { (it.height ?: 0) }
                .thenByDescending { it.width ?: 0 }
                .thenByDescending { it.bitrate ?: 0 }
        )
        Log.d(TAG, "Found ${sortedVideo.size} video variants")

        // Auto-pin the highest variant the first time we see >1 quality for
        // this stream URL. User can switch to "Auto" or another variant via
        // the quality overlay.
        val url = currentStreamUrl
        var pinnedAuto = false
        if (sortedVideo.size > 1 && url != null && autoQualityAppliedForUrl != url) {
            val best = sortedVideo.first()
            val grp = tracks.groups.getOrNull(best.groupIndex)
            if (grp != null) {
                Log.i(TAG, "Auto-selecting highest quality: ${best.displayName()} (${best.metadata()})")
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, best.trackIndex))
                    .build()
                autoQualityAppliedForUrl = url
                pinnedAuto = true
            }
        }

        _uiState.update {
            it.copy(
                audioTracks = audioTracks,
                videoTracks = sortedVideo,
                isQualityAuto = if (pinnedAuto) false else it.isQualityAuto,
            )
        }
        updateSubtitleTrackList()
    }

    /**
     * Fetches subtitles from installed Stremio subtitle addons (e.g. OpenSubtitles),
     * converts them to ExternalSubtitle, and merges with existing results.
     */
    private suspend fun fetchStremioSubtitles(
        tmdbId: Int, isMovie: Boolean, season: Int?, episode: Int?
    ) {
        try {
            val addons = StremioAddonRepository.getAddons()
            Log.i(TAG, "Stremio subtitle fetch: ${addons.size} addons installed")
            if (addons.isEmpty()) return

            // Stremio uses IMDB IDs – convert from TMDB
            val ids = if (isMovie) {
                TmdbClient.api.getMovieExternalIds(tmdbId, TmdbClient.API_KEY)
            } else {
                TmdbClient.api.getTvExternalIds(tmdbId, TmdbClient.API_KEY)
            }
            val imdbId = ids.imdbId?.takeIf { it.startsWith("tt") } ?: run {
                Log.i(TAG, "Stremio subs: no IMDB ID for tmdb=$tmdbId")
                return
            }

            val type = if (isMovie) "movie" else "series"
            val stremioId = if (isMovie) imdbId
                else if (season != null && episode != null) "$imdbId:$season:$episode"
                else imdbId

            Log.i(TAG, "Stremio subs: querying type=$type id=$stremioId")

            val stremioSubs = StremioService.getSubtitles(
                addons = addons, type = type, id = stremioId
            )
            Log.i(TAG, "Stremio subs: got ${stremioSubs.size} total")
            if (stremioSubs.isEmpty()) return

            val converted = stremioSubs.mapIndexed { idx, s ->
                val lang = s.langCode?.lowercase()
                    ?: s.lang.split(" ").firstOrNull()?.lowercase()
                    ?: s.lang.lowercase()
                val displayName = s.name ?: s.title ?: "${lang.uppercase()} ${idx + 1}"
                val format = when {
                    s.url.contains(".srt", true) -> "srt"
                    s.url.contains(".vtt", true) -> "vtt"
                    s.url.contains(".ass", true) || s.url.contains(".ssa", true) -> "ass"
                    else -> "srt"
                }
                ExternalSubtitle(
                    id = "stremio_$idx",
                    url = s.url,
                    language = lang,
                    displayName = displayName,
                    format = format,
                    source = "stremio",
                    isHearingImpaired = displayName.contains("hearing", true)
                        || displayName.contains("HI", false),
                    downloadCount = 0
                )
            }

            // Merge with existing, dedup by URL
            val existing = _uiState.value.externalSubtitles
            val existingUrls = existing.map { it.url }.toSet()
            val newSubs = converted.filter { it.url !in existingUrls }
            if (newSubs.isNotEmpty()) {
                Log.i(TAG, "Stremio addons: ${newSubs.size} new subtitles")
                _uiState.update { it.copy(externalSubtitles = existing + newSubs) }
                updateSubtitleTrackList()
            }
        } catch (e: Exception) {
            Log.i(TAG, "Stremio subtitle fetch failed: ${e.message}")
        }
    }

    private fun updateSubtitleTrackList() {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val subTracks = mutableListOf<SubtitleTrackInfo>()

        // All ExoPlayer subtitle tracks (built-in + externally added)
        for ((groupIdx, group) in tracks.groups.withIndex()) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language ?: "und"
                val label = format.label ?: languageToDisplay(lang)
                subTracks.add(SubtitleTrackInfo(
                    id = "builtin_${groupIdx}_$i",
                    label = label,
                    language = lang,
                    isBuiltIn = true,
                    isSelected = group.isTrackSelected(i),
                    groupIndex = groupIdx,
                    trackIndex = i
                ))
            }
        }

        // External subtitles not yet added to the player
        val activeCustomUrl = _uiState.value.customSubtitleSourceUrl
        for (ext in _uiState.value.externalSubtitles) {
            if (ext.url in addedExternalSubLabels) continue
            subTracks.add(SubtitleTrackInfo(
                id = ext.id,
                label = "${ext.displayName} (${ext.source})",
                language = ext.language,
                isBuiltIn = false,
                isSelected = ext.url == activeCustomUrl,
                externalUrl = ext.url,
                source = ext.source
            ))
        }

        _uiState.update { it.copy(subtitleTracks = subTracks) }
    }

    // ── Playback controls ──

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
            _uiState.update { it.copy(showPauseOverlay = false) }
            showControls()
        }
    }

    fun seekForward(ms: Long = 10_000) {
        val exo = player ?: return
        exo.seekTo((exo.currentPosition + ms).coerceAtMost(exo.duration))
    }

    fun seekBackward(ms: Long = 10_000) {
        val exo = player ?: return
        exo.seekTo((exo.currentPosition - ms).coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceIn(0, player?.duration ?: 0))
    }

    fun skipActiveSegment() {
        val seg = _uiState.value.activeSkipSegment ?: return
        val exo = player ?: return
        val target = if (seg.endMs == Long.MAX_VALUE) exo.duration else seg.endMs
        Log.i(TAG, "Skipping ${seg.type.label}: seeking to ${target}ms")
        exo.seekTo(target.coerceAtMost(exo.duration))
        _uiState.update { it.copy(activeSkipSegment = null) }
    }

    // ── Preview seek (accumulates before committing) ──

    fun previewSeekBy(deltaMs: Long) {
        val exo = player ?: return
        val currentPreview = _uiState.value.pendingPreviewSeekPosition ?: exo.currentPosition
        val newPos = (currentPreview + deltaMs).coerceIn(0, exo.duration)
        _uiState.update {
            it.copy(pendingPreviewSeekPosition = newPos, showSeekOverlay = true)
        }
        seekOverlayHideJob?.cancel()
        seekOverlayHideJob = viewModelScope.launch {
            delay(2000)
            commitPreviewSeek()
        }
    }

    fun commitPreviewSeek() {
        val pending = _uiState.value.pendingPreviewSeekPosition ?: return
        player?.seekTo(pending)
        _uiState.update {
            it.copy(pendingPreviewSeekPosition = null, showSeekOverlay = false)
        }
        seekOverlayHideJob?.cancel()
    }

    // ── Audio ──

    fun selectAudioTrack(track: AudioTrackInfo) {
        val exo = player ?: return
        val groups = exo.currentTracks.groups
        if (track.groupIndex < 0 || track.groupIndex >= groups.size) return

        val group = groups[track.groupIndex]
        Log.d(TAG, "Selecting audio track: group=${track.groupIndex}, track=${track.trackIndex}, lang=${track.language}")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
            .build()
        updateTracks()
    }

    // ── Video / Quality ──

    fun selectVideoTrack(track: VideoTrackInfo) {
        val exo = player ?: return
        val groups = exo.currentTracks.groups
        if (track.groupIndex < 0 || track.groupIndex >= groups.size) return
        val group = groups[track.groupIndex]
        Log.i(TAG, "Selecting video quality: ${track.displayName()} (${track.metadata()})")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
            .build()
        autoQualityAppliedForUrl = currentStreamUrl
        _uiState.update { it.copy(isQualityAuto = false) }
        updateTracks()
    }

    fun selectAutoQuality() {
        val exo = player ?: return
        Log.i(TAG, "Selecting Auto quality (clearing video override)")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        autoQualityAppliedForUrl = currentStreamUrl
        _uiState.update { it.copy(isQualityAuto = true) }
        updateTracks()
    }

    // ── Subtitles ──

    fun selectSubtitle(track: SubtitleTrackInfo) {
        val exo = player ?: return

        if (track.isBuiltIn) {
            // Select built-in track
            val groups = exo.currentTracks.groups
            if (track.groupIndex >= 0 && track.groupIndex < groups.size) {
                val group = groups[track.groupIndex]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
                    .build()
            }
        } else if (track.externalUrl != null) {
            // External subtitles are rendered by our own Compose overlay so
            // that subtitle-delay changes don't require the player to reload.
            // Disable ExoPlayer's text renderer for this track and parse the
            // cues into UI state.
            val cleanLabel = track.label.replace(" (${track.source})", "").trim()
            val mimeShort = when {
                track.externalUrl.contains(".vtt", true) -> "vtt"
                else -> "srt"
            }
            Log.d(TAG, "Loading external subtitle for overlay: $cleanLabel ($mimeShort) from ${track.externalUrl}")
            // Hide any built-in text track while a custom one is active.
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _uiState.update {
                it.copy(
                    customSubtitleLabel = cleanLabel,
                    customSubtitleSourceUrl = track.externalUrl,
                    customSubtitleCues = emptyList(),
                    customSubtitleLoading = true,
                )
            }
            viewModelScope.launch {
                val cues = withContext(Dispatchers.IO) {
                    SubtitleCueParser.fetchAndParse(track.externalUrl, mimeShort)
                }
                Log.d(TAG, "Parsed ${cues.size} cues for $cleanLabel")
                _uiState.update {
                    // If the user already switched to another sub, drop the result.
                    if (it.customSubtitleLabel != cleanLabel) it
                    else it.copy(customSubtitleCues = cues, customSubtitleLoading = false)
                }
                updateSubtitleTrackList()
            }
        }
        updateSubtitleTrackList()
    }

    private fun autoSelectPendingSubtitle() {
        val label = pendingSubtitleLabel ?: return
        pendingSubtitleLabel = null
        val exo = player ?: return

        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.label == label) {
                    Log.d(TAG, "Auto-selecting subtitle: $label")
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    updateSubtitleTrackList()
                    return
                }
            }
        }
    }

    fun disableSubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        // Also drop our custom overlay subtitle.
        _uiState.update {
            it.copy(
                customSubtitleCues = emptyList(),
                customSubtitleLabel = null,
                customSubtitleSourceUrl = null,
                customSubtitleLoading = false,
            )
        }
        updateSubtitleTrackList()
    }

    fun enableSubtitleTrack() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        updateSubtitleTrackList()
    }

    // ── Subtitle style ──

    fun updateSubtitleStyle(transform: (SubtitleStyleSettings) -> SubtitleStyleSettings) {
        // Subtitle delay is now consumed by the in-Compose subtitle overlay,
        // so a state update is enough — no player reload required.
        _uiState.update { it.copy(subtitleStyle = transform(it.subtitleStyle)) }
    }

    // ── Aspect ratio ──

    fun cycleAspectRatio() {
        val modes = AspectMode.entries
        val current = _uiState.value.aspectMode
        val next = modes[(current.ordinal + 1) % modes.size]
        _uiState.update { it.copy(aspectMode = next, showAspectIndicator = true) }

        viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(showAspectIndicator = false) }
        }
    }

    // ── Overlay visibility ──

    fun showControls() {
        _uiState.update { it.copy(showControls = true, showPauseOverlay = false) }
        scheduleControlsHide()
    }

    fun hideControls() {
        _uiState.update { it.copy(showControls = false) }
    }

    fun toggleControls() {
        if (_uiState.value.showControls) hideControls() else showControls()
    }

    fun dismissPauseOverlay() {
        player?.play()
        _uiState.update { it.copy(showPauseOverlay = false) }
        showControls()
    }

    fun showSubtitleOverlay() {
        _uiState.update { it.copy(showSubtitleOverlay = true, showControls = false) }
    }

    fun hideSubtitleOverlay() {
        _uiState.update { it.copy(showSubtitleOverlay = false) }
        showControls()
    }

    fun showAudioOverlay() {
        _uiState.update { it.copy(showAudioOverlay = true, showControls = false) }
    }

    fun hideAudioOverlay() {
        _uiState.update { it.copy(showAudioOverlay = false) }
        showControls()
    }

    fun showQualityOverlay() {
        _uiState.update { it.copy(showQualityOverlay = true, showControls = false) }
    }

    fun hideQualityOverlay() {
        _uiState.update { it.copy(showQualityOverlay = false) }
        showControls()
    }

    fun showSpeedOverlay() {
        _uiState.update { it.copy(showSpeedOverlay = true, showControls = false) }
    }

    fun hideSpeedOverlay() {
        _uiState.update { it.copy(showSpeedOverlay = false) }
        showControls()
    }

    /** Available playback speeds offered in the speed overlay. */
    val playbackSpeeds: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun showSubtitleStylePanel() {
        _uiState.update { it.copy(showSubtitleStylePanel = true, showControls = false) }
    }

    fun hideSubtitleStylePanel() {
        _uiState.update { it.copy(showSubtitleStylePanel = false) }
        showControls()
    }

    fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        controlsHideJob = viewModelScope.launch {
            delay(5000)
            if (_uiState.value.isPlaying &&
                !_uiState.value.showSubtitleOverlay &&
                !_uiState.value.showAudioOverlay &&
                !_uiState.value.showQualityOverlay &&
                !_uiState.value.showSubtitleStylePanel
            ) {
                _uiState.update { it.copy(showControls = false) }
            }
        }
    }

    private fun startPositionUpdater() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                // An uncaught exception in a viewModelScope coroutine crashes the
                // whole app (which looks like the player "randomly exiting" to the
                // detail page). This ticks every 200ms against a player that can be
                // released underneath it — never let a tick take the app down.
                try {
                player?.let { exo ->
                    val pos = exo.currentPosition.coerceAtLeast(0)
                    val dur = exo.duration

                    // Auto-seek to remembered position once duration is known.
                    pendingSeekMs?.let { target ->
                        if (dur > 0) {
                            val safe = target.coerceIn(0L, (dur - 5_000L).coerceAtLeast(0L))
                            Log.i(TAG, "Auto-seek (resume) to ${safe}ms (duration=$dur)")
                            exo.seekTo(safe)
                            pendingSeekMs = null
                        }
                    }

                    val segments = _uiState.value.skipSegments
                    val active = segments.find { seg ->
                        val end = if (seg.endMs == Long.MAX_VALUE) exo.duration else seg.endMs
                        pos in seg.startMs until end
                    }
                    _uiState.update { it.copy(currentPosition = pos, activeSkipSegment = active) }

                    // Throttled progress save (~every 3s while we're past auto-seek).
                    val now = System.currentTimeMillis()
                    if (pendingSeekMs == null && now - lastProgressSaveAt > 3_000L) {
                        lastProgressSaveAt = now
                        saveCurrentProgress(pos, dur.coerceAtLeast(0L))
                    }

                    // Show the Up Next overlay once we pass the midpoint.
                    maybeShowUpNext(pos, dur.coerceAtLeast(0L))
                }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Position tick failed (ignored): ${e.message}")
                }
                delay(200)
            }
        }
    }

    private fun startStatsPoller(hash: String) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (true) {
                val stats = TorrServerService.getTorrentStats(hash)
                if (stats != null) {
                    _uiState.update {
                        it.copy(speedMbps = stats.speedMbps, activePeers = stats.activePeers)
                    }
                }
                delay(3000)
            }
        }
    }

    private fun languageToDisplay(code: String): String {
        return try {
            java.util.Locale(code).displayLanguage
        } catch (_: Exception) { code }
    }

    override fun onCleared() {
        super.onCleared()

        // Final progress save before tearing things down.
        try {
            val exo = player
            if (exo != null) {
                saveCurrentProgress(exo.currentPosition.coerceAtLeast(0L), exo.duration.coerceAtLeast(0L))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Final progress save failed: ${e.message}")
        }

        positionJob?.cancel()
        controlsHideJob?.cancel()
        statsJob?.cancel()
        seekOverlayHideJob?.cancel()
        streamRetryJob?.cancel()
        iptvWatchdogJob?.cancel()
        vodWatchdogJob?.cancel()
        player?.release()
        player = null

        // Clean up torrent on a non-viewModelScope thread
        _uiState.value.torrentHash?.let { hash ->
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                TorrServerService.removeTorrent(hash)
            }
        }
    }

    /**
     * Public hook so the hosting Activity can flush the latest progress
     * synchronously (e.g. from onPause) before another Activity resumes
     * and re-reads the store.
     */
    fun flushProgress() {
        try {
            val exo = player ?: return
            saveCurrentProgress(
                exo.currentPosition.coerceAtLeast(0L),
                exo.duration.coerceAtLeast(0L),
            )
        } catch (e: Exception) {
            Log.w(TAG, "flushProgress failed: ${e.message}")
        }
    }

    /**
     * Builds a [WatchProgress] from the current UI state + resume context and
     * upserts it into the store. Silently no-ops when there is not enough info
     * (no tmdbId AND no addonId / stremioId) or when the position is too small.
     */
    private fun saveCurrentProgress(positionMs: Long, durationMs: Long) {
        if (!AppPreferences.playHistoryEnabled) return
        if (positionMs <= 0L) return
        val s = _uiState.value
        if (s.title.isBlank()) return

        val kind = when {
            resumeAnimeId != null -> WatchKind.ANIME
            resumeAddonId != null -> WatchKind.ADDON_STREAM
            currentMagnetUri != null -> WatchKind.MAGNET
            s.isStreamingMode -> WatchKind.STREAMING
            else -> return
        }

        // Don't clutter Home's Continue Watching with transient plays that lack
        // proper library metadata: addon-catalog streams, and torrent-hub magnets
        // (tmdbId <= 0). Detail-screen torrents carry a real tmdbId and still resume.
        if (kind == WatchKind.ADDON_STREAM) return
        if (kind == WatchKind.MAGNET && s.tmdbId <= 0) return

        // For ADDON_STREAM with no tmdb, we still need stremio key parts.
        if (s.tmdbId <= 0 &&
            (kind == WatchKind.ADDON_STREAM &&
                (resumeAddonId.isNullOrBlank() ||
                resumeStremioId.isNullOrBlank()))
        ) return

        val key = WatchProgress.makeKey(
            kind = kind,
            tmdbId = s.tmdbId,
            isMovie = s.isMovie,
            seasonNumber = s.seasonNumber,
            episodeNumber = s.episodeNumber,
            addonId = resumeAddonId,
            stremioType = resumeStremioType,
            stremioId = resumeStremioId,
            animeId = resumeAnimeId,
        )

        val entry = WatchProgress(
            key = key,
            kind = kind,
            tmdbId = s.tmdbId,
            imdbId = resumeImdbId,
            isMovie = s.isMovie,
            title = s.title,
            episodeTitle = s.episodeTitle,
            seasonNumber = s.seasonNumber,
            episodeNumber = s.episodeNumber,
            posterUrl = resumePosterUrl,
            backdropUrl = s.backdropUrl,
            logoUrl = s.logoUrl,
            year = s.year,
            rating = s.rating,
            overview = s.overview,
            sourceIndex = if (kind == WatchKind.STREAMING) s.currentSourceIndex else null,
            magnetUri = if (kind == WatchKind.MAGNET) currentMagnetUri else null,
            fileIdx = if (kind == WatchKind.MAGNET) resumeFileIdx else null,
            addonId = resumeAddonId,
            stremioType = resumeStremioType,
            stremioId = resumeStremioId,
            streamPickKey = resumeStreamPickKey,
            streamPickName = resumeStreamPickName,
            animeId = resumeAnimeId,
            animeCategory = resumeAnimeCategory,
            streamUrl = s.streamUrl,
            streamReferer = s.referer,
            animeOrigin = s.animeOrigin,
            animeTracksJson = s.animeTracksJson,
            animeEmbedsJson = s.animeEmbedsJson,
            animeServer = s.animeServer,
            animeEmbedUrl = s.animeEmbedUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
        )

        try {
            WatchProgressStore.upsert(entry)
        } catch (e: Exception) {
            Log.w(TAG, "saveCurrentProgress failed: ${e.message}")
        }
    }

    // ── STREAMING MODE ────────────────────────────────────────────────────────

    fun initStreamingPlayer(
        streamUrl: String,
        referer: String,
        sourceIndex: Int,
        title: String,
        logoUrl: String?,
        backdropUrl: String?,
        year: String?,
        rating: String?,
        overview: String?,
        isMovie: Boolean,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        tmdbId: Int,
        isIptv: Boolean = false,
        animeTracksJson: String? = null,
        animeOrigin: String? = null,
        animeEmbedsJson: String? = null,
        animeServer: String? = null,
        animeEmbedUrl: String? = null,
        animeId: String? = null,
        animeCategory: String? = null,
        customHeadersJson: String? = null,
    ) {
        if (player != null) return
        failedSourceIndices.clear()
        autoAdvanceSources = true
        setSourceStatus(sourceIndex, "ok")

        customStreamHeaders = parseHeadersJson(customHeadersJson)

        resumeAnimeId = animeId
        resumeAnimeCategory = animeCategory

        var decodedEmbeds: List<com.playtorrio.tv.data.anime.AnimeEmbed>? = null
        var currentServer: String? = animeServer
        var currentEmbedUrl: String? = animeEmbedUrl
        if (!animeEmbedsJson.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(animeEmbedsJson)
                val list = mutableListOf<com.playtorrio.tv.data.anime.AnimeEmbed>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(com.playtorrio.tv.data.anime.AnimeEmbed(
                        url = obj.getString("url"),
                        server = obj.getString("server"),
                        category = obj.getString("category"),
                        label = obj.getString("label")
                    ))
                }
                decodedEmbeds = list
                if (currentServer == null) {
                    currentServer = list.find { it.url == streamUrl }?.server ?: list.firstOrNull()?.server
                }
                if (currentEmbedUrl == null) {
                    currentEmbedUrl = list.find { it.url == streamUrl }?.url ?: list.firstOrNull()?.url
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to parse animeEmbedsJson: ${e.message}")
            }
        }

        _uiState.update {
            it.copy(
                isConnecting = false,
                isPlaying = false,
                title = title,
                logoUrl = logoUrl,
                backdropUrl = backdropUrl,
                year = year,
                rating = rating,
                overview = overview,
                isMovie = isMovie,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                tmdbId = tmdbId,
                isStreamingMode = true,
                isMagnet = false,
                currentSourceIndex = sourceIndex,
                streamUrl = streamUrl,
                referer = referer,
                animeOrigin = animeOrigin,
                animeTracksJson = animeTracksJson,
                animeEmbedsJson = animeEmbedsJson,
                animeServer = animeServer,
                animeEmbedUrl = animeEmbedUrl,
                isIptv = isIptv,
                animeEmbeds = decodedEmbeds,
                currentAnimeServer = currentServer,
                currentAnimeEmbedUrl = currentEmbedUrl,
            )
        }

        val context = getApplication<Application>()
        viewModelScope.launch {
            // IPTV streams have no TMDB context — skip subtitle/skip-segment/episode fetches.
            if (!isIptv) {
                launch {
                    val subs = SubtitleService.fetchSubtitles(
                        tmdbId = tmdbId,
                        season = seasonNumber,
                        episode = episodeNumber,
                        title = _uiState.value.title.takeIf { it.isNotBlank() },
                        year = _uiState.value.year?.toIntOrNull(),
                    )
                    _uiState.update { it.copy(externalSubtitles = subs) }
                    updateSubtitleTrackList()

                    // Also fetch from Stremio subtitle addons
                    fetchStremioSubtitles(tmdbId, isMovie, seasonNumber, episodeNumber)
                }

                launch {
                    val segments = SkipSegmentService.fetchSegments(tmdbId, isMovie, seasonNumber, episodeNumber)
                    _uiState.update { it.copy(skipSegments = segments) }
                    Log.i(TAG, "Skip segments loaded (streaming): ${segments.size}")
                }
                if (!isMovie && seasonNumber != null) {
                    launch { loadEpisodesForCurrentSeries() }
                }
            }
            withContext(Dispatchers.Main) {
                if (isIptv) {
                    createIptvPlayer(streamUrl, referer)
                } else {
                    createStreamingPlayer(
                        streamUrl = streamUrl,
                        referer = referer,
                        animeTracksJson = animeTracksJson,
                        animeOrigin = animeOrigin,
                    )
                }
            }
            if (!isIptv) maybeLoadSuggestions()
        }
    }

    private fun createStreamingPlayer(
        streamUrl: String,
        referer: String,
        animeTracksJson: String? = null,
        animeOrigin: String? = null,
    ) {
        val context = getApplication<Application>()
        resetStartupRetryState()
        currentReferer = referer

        // New stream → drop any in-overlay subtitle from the previous one.
        _uiState.update {
            it.copy(
                customSubtitleCues = emptyList(),
                customSubtitleLabel = null,
                customSubtitleSourceUrl = null,
                customSubtitleLoading = false,
            )
        }

        val headers = buildMap<String, String> {
            if (referer.isNotBlank()) {
                put("Referer", referer)
                // Derive origin as scheme + host (e.g. "https://lordflix.org")
                val origin = animeOrigin ?: try {
                    val uri = android.net.Uri.parse(referer)
                    "${uri.scheme}://${uri.host}"
                } catch (_: Exception) { referer.trimEnd('/') }
                put("Origin", origin)
            }
            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
            // Stream-declared headers (behaviorHints.proxyHeaders) win — some
            // addon CDNs (e.g. NoTorrent's "MPV" streams) whitelist a specific
            // player User-Agent and 403 everything else.
            putAll(customStreamHeaders)
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)

        // Wrap the HttpDataSource.Factory to hide HTTP response code exceptions from ExoPlayer.
        // ExoPlayer 1.10 HlsChunkSource has a fatal bug where it tries to iterate master playlist variants
        // using track selection indices if a playlist load error (e.g., 404/403) occurs, causing OOB crash.
        // Throwing a generic IOException forces ExoPlayer to treat it as a network error rather than a playlist
        // fallback scenario, bypassing the bug entirely.
        val customHttpFactory = androidx.media3.datasource.DataSource.Factory {
            val dataSource = dataSourceFactory.createDataSource()
            object : androidx.media3.datasource.DataSource by dataSource {
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    try {
                        return dataSource.open(dataSpec)
                    } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        throw java.io.IOException("Fatal HTTP Error ${e.responseCode} (Hidden from ExoPlayer to prevent fallback crash)", e)
                    }
                }
            }
        }

        // Anime fails fast (it has alternate embeds to fall back to); regular
        // streaming is patient so a transient network slowdown re-buffers and
        // keeps playing instead of dying and exiting the player.
        val isAnime = _uiState.value.animeEmbeds != null
        val maxLoadRetries = if (isAnime) 3 else 8
        val errorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxLoadRetries
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long {
                val attempt = loadErrorInfo.errorCount.coerceAtLeast(1)
                if (attempt > maxLoadRetries) return androidx.media3.common.C.TIME_UNSET
                return (1_500L * attempt).coerceAtMost(8_000L)
            }
            override fun getFallbackSelectionFor(
                fallbackOptions: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
            ): androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection? {
                // Prevent ExoPlayer HLS index out of bounds crash for single-variant streams
                return null
            }
        }

        val msFactory = DefaultMediaSourceFactory(customHttpFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(msFactory)
            .setLoadControl(loadControl)
            .build()
        player = exo

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exo.setAudioAttributes(audioAttrs, false)
        exo.volume = 1.0f
        exo.setPlaybackSpeed(_uiState.value.playbackSpeed)

        currentStreamUrl = streamUrl
        autoQualityAppliedForUrl = null
        // Let ExoPlayer auto-detect from Content-Type header.
        val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)

        if (!animeTracksJson.isNullOrBlank()) {
            try {
                val subConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
                val arr = org.json.JSONArray(animeTracksJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val url = obj.getString("url")
                    val label = obj.getString("label")
                    val isDefault = obj.optBoolean("isDefault", false)
                    
                    subConfigs.add(
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                            .setMimeType(if (url.endsWith(".vtt")) androidx.media3.common.MimeTypes.TEXT_VTT else androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                            .setLabel(label)
                            .setLanguage(if (label.contains("eng", true) || label.contains("en", true)) "en" else "und")
                            .setSelectionFlags(if (isDefault || i == 0) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    )
                }
                if (subConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subConfigs)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to parse animeTracksJson: ${e.message}")
            }
        }

        exo.setMediaItem(mediaItemBuilder.build())
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = guarded("streaming.stateChanged") {
                _uiState.update {
                    it.copy(
                        isBuffering = state == Player.STATE_BUFFERING,
                        isPlaying = exo.isPlaying,
                        isConnecting = if (state == Player.STATE_READY) false else it.isConnecting
                    )
                }
                if (state == Player.STATE_READY) {
                    resetStartupRetryState()
                    setSourceStatus(_uiState.value.currentSourceIndex, "ok")
                    _uiState.update {
                        it.copy(
                            duration = exo.duration.coerceAtLeast(0),
                            isConnecting = false,
                            isReconnecting = false,
                            reconnectStatus = ""
                        )
                    }
                    updateTracks()
                    autoSelectPendingSubtitle()
                }
                if (state == Player.STATE_ENDED) onVodEnded()
            }

            override fun onIsPlayingChanged(playing: Boolean) = guarded("streaming.isPlaying") {
                _uiState.update { it.copy(isPlaying = playing) }
                if (!playing && exo.playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) = guarded("streaming.tracks") { updateTracks() }

            override fun onPlayerError(error: PlaybackException) = guarded("streaming.error") {
                Log.w(TAG, "Playback error (createStreamingPlayer): ${error.errorCodeName}", error)
                scheduleStreamStartupRetry(exo, error)
            }
        })

        startPositionUpdater()
        startVodWatchdog(exo)
        scheduleControlsHide()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  IPTV player path — dedicated builder with stall watchdog + tiered
    //  recovery. Used whenever isIptv=true. Live IPTV portals are flaky:
    //  segments stop arriving, sockets die silently, and ExoPlayer often
    //  doesn't surface a PlaybackException for these freezes. We watch
    //  player state ourselves and escalate recovery in stages.
    // ─────────────────────────────────────────────────────────────────────────
    private fun createIptvPlayer(streamUrl: String, referer: String) {
        val context = getApplication<Application>()
        resetStartupRetryState()
        stopVodWatchdog()
        stopIptvWatchdog()
        iptvRetryAttempt = 0
        currentReferer = referer
        currentStreamUrl = streamUrl
        autoQualityAppliedForUrl = null

        val headers = buildMap<String, String> {
            if (referer.isNotBlank()) {
                put("Referer", referer)
                val origin = try {
                    val uri = android.net.Uri.parse(referer)
                    "${uri.scheme}://${uri.host}"
                } catch (_: Exception) { referer.trimEnd('/') }
                put("Origin", origin)
            }
            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
            // Stream-declared headers (behaviorHints.proxyHeaders) win — some
            // addon CDNs (e.g. NoTorrent's "MPV" streams) whitelist a specific
            // player User-Agent and 403 everything else.
            putAll(customStreamHeaders)
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)

        // Custom error policy: be very patient with transient HTTP errors that
        // are typical of overloaded IPTV portals (502/503/504, socket resets).
        val errorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long {
                // Exponential-ish backoff capped at 5s so we keep retrying
                // chunk loads aggressively but don't hammer a dead portal.
                val attempt = loadErrorInfo.errorCount.coerceAtLeast(1)
                return (1_000L * attempt).coerceAtMost(5_000L)
            }
            override fun getFallbackSelectionFor(
                fallbackOptions: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
            ): androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection? {
                // Prevent ExoPlayer HLS index out of bounds crash for single-variant streams
                return null
            }
        }

        val msFactory = DefaultMediaSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)

        // Live-tuned buffer: small + reactive. Big buffers hide stalls and
        // delay our watchdog's reaction.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                4_000,    // min buffer
                30_000,   // max buffer
                1_500,    // playback start threshold
                3_000     // rebuffer threshold
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(
                parameters.buildUpon()
                    .clearViewportSizeConstraints()
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
            )
        }

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(msFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
        player = exo

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exo.setAudioAttributes(audioAttrs, false)
        exo.volume = 1.0f

        Log.i(TAG, "Creating IPTV player for $streamUrl")
        exo.setMediaItem(MediaItem.fromUri(streamUrl))
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = guarded("iptv.stateChanged") {
                _uiState.update {
                    it.copy(
                        isBuffering = state == Player.STATE_BUFFERING,
                        isPlaying = exo.isPlaying,
                        isConnecting = if (state == Player.STATE_READY) false else it.isConnecting
                    )
                }
                when (state) {
                    Player.STATE_BUFFERING -> {
                        if (iptvBufferingSinceMs == 0L) {
                            iptvBufferingSinceMs = System.currentTimeMillis()
                        }
                    }
                    Player.STATE_READY -> {
                        iptvBufferingSinceMs = 0L
                        iptvLastHealthyAt = System.currentTimeMillis()
                        // Try to restore the wallclock position we were at
                        // before recovery, so the user doesn't miss the
                        // seconds spent reconnecting. Falls back silently if
                        // the stream has no PROGRAM-DATE-TIME / DVR window.
                        tryRestoreIptvPosition(exo)
                        _uiState.update {
                            it.copy(
                                duration = exo.duration.coerceAtLeast(0),
                                isConnecting = false,
                                isReconnecting = false,
                                reconnectStatus = ""
                            )
                        }
                        updateTracks()
                        autoSelectPendingSubtitle()
                    }
                    Player.STATE_ENDED -> {
                        // Live streams should never END normally — treat as a stall.
                        Log.w(TAG, "IPTV stream ended unexpectedly — recovering")
                        recoverIptvStream("stream-ended")
                    }
                    else -> { /* IDLE handled by error/recovery path */ }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) = guarded("iptv.isPlaying") {
                _uiState.update { it.copy(isPlaying = playing) }
                if (!playing && exo.playbackState == Player.STATE_READY) {
                    _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) = guarded("iptv.tracks") { updateTracks() }

            override fun onPlayerError(error: PlaybackException) = guarded("iptv.error") {
                Log.w(TAG, "IPTV playback error: ${error.errorCodeName}", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Standard Media3 fix: jump to live edge and re-prepare.
                    Log.i(TAG, "BEHIND_LIVE_WINDOW — seeking to live edge")
                    try {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    } catch (e: Exception) {
                        Log.w(TAG, "BEHIND_LIVE_WINDOW recovery failed: ${e.message}")
                        recoverIptvStream("behind-live-window-failed")
                    }
                    return@guarded
                }
                recoverIptvStream("error:${error.errorCodeName}")
            }
        })

        startIptvWatchdog(exo)
        startPositionUpdater()
        scheduleControlsHide()
    }

    private fun startIptvWatchdog(exo: ExoPlayer) {
        stopIptvWatchdog()
        iptvLastPositionMs = -1L
        iptvLastPositionAt = System.currentTimeMillis()
        iptvLastHealthyAt = System.currentTimeMillis()
        iptvHealthyStreakStartMs = 0L
        iptvBufferingSinceMs = 0L

        iptvWatchdogJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val current = player ?: return@launch
                if (current !== exo) return@launch  // player rebuilt — old watchdog dies
                if (!_uiState.value.isIptv) return@launch

                val now = System.currentTimeMillis()
                val state = current.playbackState
                val pos = current.currentPosition

                // ── Position tracking + healthy streak ────────────────────
                if (state == Player.STATE_READY && current.isPlaying) {
                    if (pos != iptvLastPositionMs) {
                        iptvLastPositionMs = pos
                        iptvLastPositionAt = now
                        iptvLastHealthyAt = now
                        if (iptvHealthyStreakStartMs == 0L) iptvHealthyStreakStartMs = now
                        if (iptvRetryAttempt > 0 &&
                            (now - iptvHealthyStreakStartMs) >= iptvHealthyResetMs
                        ) {
                            Log.i(TAG, "IPTV healthy streak — resetting retry counter (was $iptvRetryAttempt)")
                            iptvRetryAttempt = 0
                        }
                    } else {
                        iptvHealthyStreakStartMs = 0L
                    }
                } else {
                    iptvHealthyStreakStartMs = 0L
                }

                // ── Stall detector 1: stuck buffering ──────────────────────
                if (state == Player.STATE_BUFFERING && current.playWhenReady && iptvBufferingSinceMs > 0L) {
                    val bufferedFor = now - iptvBufferingSinceMs
                    if (bufferedFor >= iptvBufferingStallMs) {
                        Log.w(TAG, "IPTV buffering stall detected (${bufferedFor}ms) — recovering")
                        iptvBufferingSinceMs = 0L
                        recoverIptvStream("buffering-stall")
                        continue
                    }
                }

                // ── Stall detector 2: STATE_READY + isPlaying but position frozen ─────
                // Gated on iptvLastPositionMs >= 0L so we don't fire before the first
                // real position tick — avoids false positives during initial buffering.
                if (state == Player.STATE_READY && current.isPlaying && iptvLastPositionMs >= 0L) {
                    val frozenFor = now - iptvLastPositionAt
                    if (frozenFor >= iptvFrozenPositionMs) {
                        Log.w(TAG, "IPTV position frozen for ${frozenFor}ms at $pos — recovering")
                        iptvLastPositionAt = now
                        recoverIptvStream("position-frozen")
                        continue
                    }
                }

                // ── Stall detector 3: READY + playWhenReady but not playing ────────
                // Catches audio focus loss, surface theft, renderer stalls — cases
                // where ExoPlayer reports READY but isPlaying stays false.
                if (state == Player.STATE_READY && current.playWhenReady && !current.isPlaying) {
                    val stalledFor = now - iptvLastHealthyAt
                    if (stalledFor >= iptvFrozenPositionMs) {
                        Log.w(TAG, "IPTV ready+playWhenReady but not playing for ${stalledFor}ms — recovering")
                        iptvLastHealthyAt = now
                        recoverIptvStream("ready-not-playing")
                        continue
                    }
                }
            }
        }
    }

    private var lastIptvRecoveryAt: Long = 0L

    private fun stopIptvWatchdog() {
        iptvWatchdogJob?.cancel()
        iptvWatchdogJob = null
    }

    /**
     * After an IPTV recovery completes (player reaches STATE_READY), try to
     * seek back to the wallclock position the user was at when the stall hit.
     * This avoids losing the seconds we spent reconnecting.
     *
     * Mechanics: we saved a unix timestamp before recovery (windowStartTimeMs
     * + currentPosition). The new live window has its own windowStartTimeMs.
     * The position within the new window that corresponds to our saved unix
     * time is `savedUnix - newWindow.windowStartTimeMs`. If that value falls
     * within the new window's duration, we seek there. Otherwise we leave the
     * player at its default (live edge).
     *
     * Only works when the upstream emits PROGRAM-DATE-TIME (HLS) or has a
     * known availabilityStartTime (DASH). Most public IPTV portals do.
     */
    private fun tryRestoreIptvPosition(exo: ExoPlayer) {
        val savedUnix = iptvSavedUnixMs
        if (savedUnix == C.TIME_UNSET) return
        // One-shot: clear so we don't re-seek on subsequent STATE_READY events
        // (e.g. after the seek itself triggers BUFFERING → READY).
        iptvSavedUnixMs = C.TIME_UNSET
        try {
            val timeline = exo.currentTimeline
            if (timeline.isEmpty) return
            val win = timeline.getWindow(exo.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
            val newStart = win.windowStartTimeMs
            if (newStart == C.TIME_UNSET) return
            val target = savedUnix - newStart
            val winDur = win.durationMs
            if (winDur == C.TIME_UNSET || winDur <= 0L) return
            // Clamp inside window with a small safety margin from the live edge
            // so we don't immediately bump into BehindLiveWindowException at the
            // tail or trigger another recovery at the head.
            val safeTarget = target.coerceIn(2_000L, (winDur - 2_000L).coerceAtLeast(2_000L))
            // Only resume if the saved position actually falls inside the new
            // window — if recovery took longer than the DVR window, our spot
            // has scrolled out and the best we can do is the live edge.
            if (target < 0L || target > winDur) {
                Log.i(TAG, "IPTV restore skipped: saved offset=$target outside window 0..$winDur")
                return
            }
            Log.i(TAG, "IPTV restore: seeking to ${safeTarget}ms (savedUnix=$savedUnix, newStart=$newStart, winDur=$winDur)")
            exo.seekTo(safeTarget)
        } catch (e: Exception) {
            Log.w(TAG, "IPTV restore failed: ${e.message}")
        }
    }

    /**
     * Tiered IPTV recovery:
     *   Attempt 1-2: seekToDefaultPosition() + prepare()  — cheapest, fixes
     *               most live-edge / dropped-segment hiccups.
     *   Attempt 3-4: stop() + clearMediaItems() + setMediaItem() + prepare()
     *               — fresh MediaSource, new HTTP connections.
     *   Attempt 5+:  release() and rebuild via createIptvPlayer() — what
     *               "exit and reopen the channel" does manually.
     *   After iptvMaxRetries, surface error.
     */
    private fun recoverIptvStream(reason: String) {
        val exo = player ?: return
        if (!_uiState.value.isIptv) return
        val url = currentStreamUrl ?: return
        val referer = currentReferer

        val now = System.currentTimeMillis()
        // Throttle: never run two recoveries within 1.5s of each other.
        if (now - lastIptvRecoveryAt < 1_500L) {
            Log.i(TAG, "IPTV recovery throttled (reason=$reason)")
            return
        }
        lastIptvRecoveryAt = now

        if (iptvRetryAttempt >= iptvMaxRetries) {
            Log.e(TAG, "IPTV recovery exhausted after $iptvRetryAttempt attempts (reason=$reason)")
            stopIptvWatchdog()
            _uiState.update {
                it.copy(
                    isConnecting = false,
                    isReconnecting = false,
                    reconnectStatus = "",
                    error = "Stream unavailable. Please try another source."
                )
            }
            return
        }

        iptvRetryAttempt += 1
        val attempt = iptvRetryAttempt

        // Capture the wallclock position we're currently at so we can resume
        // exactly here after recovery (instead of jumping forward to the new
        // live edge and losing the seconds spent reconnecting). Only works
        // for streams that expose a windowStartTimeMs (HLS with EXT-X-PROGRAM-
        // DATE-TIME or DASH with availabilityStartTime). For others this
        // stays C.TIME_UNSET and we fall back to the live edge.
        iptvSavedUnixMs = try {
            val timeline = exo.currentTimeline
            if (!timeline.isEmpty) {
                val win = timeline.getWindow(exo.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
                if (win.windowStartTimeMs != C.TIME_UNSET) {
                    val pos = exo.currentPosition.coerceAtLeast(0L)
                    win.windowStartTimeMs + pos
                } else C.TIME_UNSET
            } else C.TIME_UNSET
        } catch (_: Exception) { C.TIME_UNSET }
        if (iptvSavedUnixMs != C.TIME_UNSET) {
            Log.i(TAG, "IPTV recovery saved unix=$iptvSavedUnixMs (will try to resume)")
        }

        // Exponential-ish backoff with a cap.
        val backoffMs = when (attempt) {
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            4 -> 3_000L
            5 -> 4_000L
            6 -> 6_000L
            else -> 8_000L
        }

        _uiState.update {
            it.copy(
                isReconnecting = true,
                reconnectStatus = "Reconnecting… ($attempt/$iptvMaxRetries)"
            )
        }
        Log.w(TAG, "IPTV recover #$attempt (reason=$reason, backoff=${backoffMs}ms)")

        viewModelScope.launch {
            delay(backoffMs)
            // Player may have been rebuilt or released during the delay — always
            // bail if the instance changed; the new player has its own watchdog.
            val cur = player ?: return@launch
            if (cur !== exo) return@launch
            if (!_uiState.value.isIptv) return@launch

            // Reset trackers BEFORE touching the player so a watchdog tick
            // racing the listener can't trigger another recovery on stale data.
            iptvBufferingSinceMs = 0L
            iptvLastPositionMs = -1L
            iptvLastPositionAt = System.currentTimeMillis()
            iptvHealthyStreakStartMs = 0L

            try {
                when {
                    attempt <= 2 -> {
                        // Tier 1: seek to live edge + re-prepare.
                        Log.i(TAG, "IPTV recover tier 1: seekToDefault + prepare")
                        cur.seekToDefaultPosition()
                        cur.prepare()
                        cur.playWhenReady = true
                    }
                    attempt <= 4 -> {
                        // Tier 2: full re-prepare with fresh MediaItem.
                        Log.i(TAG, "IPTV recover tier 2: full re-prepare")
                        cur.stop()
                        cur.clearMediaItems()
                        cur.setMediaItem(MediaItem.fromUri(url))
                        cur.prepare()
                        cur.playWhenReady = true
                    }
                    else -> {
                        // Tier 3: nuke and rebuild player from scratch.
                        Log.i(TAG, "IPTV recover tier 3: rebuild player")
                        stopIptvWatchdog()
                        try { cur.release() } catch (_: Exception) {}
                        if (player === cur) player = null
                        // Preserve the current attempt counter across rebuild
                        // so we don't loop forever if rebuild also stalls.
                        val savedAttempt = iptvRetryAttempt
                        createIptvPlayer(url, referer)
                        iptvRetryAttempt = savedAttempt
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "IPTV recover attempt $attempt threw: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  VOD stall watchdog — attached by createPlayer / createStreamingPlayer.
    //  IPTV uses its own (createIptvPlayer); this one never runs for isIptv.
    // ─────────────────────────────────────────────────────────────────────────
    private fun stopVodWatchdog() {
        vodWatchdogJob?.cancel()
        vodWatchdogJob = null
    }

    private fun startVodWatchdog(exo: ExoPlayer) {
        stopVodWatchdog()
        vodRecoveryAttempt = 0
        lastVodRecoveryAt = 0L
        vodBufferingSinceMs = 0L
        vodLastPositionMs = -1L
        vodLastPositionAt = System.currentTimeMillis()
        vodLastHealthyAt = System.currentTimeMillis()
        vodHealthyStreakStartMs = 0L

        vodWatchdogJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val current = player ?: return@launch
                if (current !== exo) return@launch   // player rebuilt — old watchdog dies
                val s = _uiState.value
                if (s.isIptv) return@launch           // IPTV has its own watchdog

                // Don't interfere while another flow already owns the player, or
                // while we're mid resume-seek (pendingSeekMs auto-seek in flight).
                if (s.isConnecting || s.isSwitchingSource || s.isSwitchingEpisode ||
                    pendingSeekMs != null
                ) continue

                // A user-initiated pause must never trigger recovery.
                if (!current.playWhenReady) {
                    vodBufferingSinceMs = 0L
                    vodHealthyStreakStartMs = 0L
                    continue
                }

                val now = System.currentTimeMillis()
                val state = current.playbackState
                val pos = current.currentPosition

                // ── Buffering-start bookkeeping ────────────────────────────
                if (state == Player.STATE_BUFFERING) {
                    if (vodBufferingSinceMs == 0L) vodBufferingSinceMs = now
                } else {
                    vodBufferingSinceMs = 0L
                }

                // ── Position progress + healthy-streak / retry reset ───────
                if (state == Player.STATE_READY && current.isPlaying) {
                    if (pos != vodLastPositionMs) {
                        vodLastPositionMs = pos
                        vodLastPositionAt = now
                        vodLastHealthyAt = now
                        if (vodHealthyStreakStartMs == 0L) vodHealthyStreakStartMs = now
                        if (_uiState.value.isReconnecting) {
                            _uiState.update { it.copy(isReconnecting = false, reconnectStatus = "") }
                        }
                        if (vodRecoveryAttempt > 0 &&
                            (now - vodHealthyStreakStartMs) >= vodHealthyResetMs
                        ) {
                            Log.i(TAG, "VOD healthy streak — resetting retry counter (was $vodRecoveryAttempt)")
                            vodRecoveryAttempt = 0
                        }
                    } else {
                        vodHealthyStreakStartMs = 0L
                    }
                } else {
                    vodHealthyStreakStartMs = 0L
                }

                // ── Detector 1: stuck buffering (torrents get a longer grace) ─
                if (state == Player.STATE_BUFFERING && vodBufferingSinceMs > 0L) {
                    val stallLimit = if (s.isMagnet) vodMagnetBufferingStallMs else vodBufferingStallMs
                    val bufferedFor = now - vodBufferingSinceMs
                    if (bufferedFor >= stallLimit) {
                        Log.w(TAG, "VOD buffering stall (${bufferedFor}ms) — recovering")
                        vodBufferingSinceMs = 0L
                        recoverVodStream("buffering-stall")
                        continue
                    }
                }

                // ── Detector 2: READY + isPlaying but position frozen ──────
                if (state == Player.STATE_READY && current.isPlaying && vodLastPositionMs >= 0L) {
                    val frozenFor = now - vodLastPositionAt
                    if (frozenFor >= vodFrozenPositionMs) {
                        Log.w(TAG, "VOD position frozen ${frozenFor}ms at $pos — recovering")
                        vodLastPositionAt = now
                        recoverVodStream("position-frozen")
                        continue
                    }
                }

                // ── Detector 3: READY + playWhenReady but not playing ──────
                if (state == Player.STATE_READY && !current.isPlaying) {
                    val stalledFor = now - vodLastHealthyAt
                    if (stalledFor >= vodFrozenPositionMs) {
                        Log.w(TAG, "VOD ready+playWhenReady but not playing ${stalledFor}ms — recovering")
                        vodLastHealthyAt = now
                        recoverVodStream("ready-not-playing")
                        continue
                    }
                }
            }
        }
    }

    /**
     * Tiered VOD recovery — always resumes at the frozen position:
     *   Attempt 1-2: re-prepare in place (cheapest; fixes transient chunk-loader
     *                and renderer stalls; ExoPlayer keeps its position).
     *   Attempt 3-4: full stop + fresh MediaItem, resuming via pendingSeekMs.
     *   Exhausted:   streaming mode → fall back to the next source (position
     *                preserved); otherwise surface a "try another source" error.
     */
    private fun recoverVodStream(reason: String) {
        val exo = player ?: return
        val s = _uiState.value
        if (s.isIptv) return
        val url = currentStreamUrl ?: return

        val now = System.currentTimeMillis()
        if (now - lastVodRecoveryAt < 2_000L) {
            Log.i(TAG, "VOD recovery throttled (reason=$reason)")
            return
        }
        lastVodRecoveryAt = now

        val resumePos = exo.currentPosition.coerceAtLeast(0L)

        if (vodRecoveryAttempt >= vodMaxRetries) {
            // Never terminal — keep going until the user exits deliberately.
            vodRecoveryAttempt = 0
            if (s.isStreamingMode && !s.isMagnet) {
                Log.w(TAG, "VOD recovery cycling — switching source (reason=$reason)")
                if (resumePos > 5_000L) pendingSeekMs = resumePos
                lastVodRecoveryAt = now
                _uiState.update {
                    it.copy(isReconnecting = true, reconnectStatus = "Reconnecting…", error = null)
                }
                tryNextSource("stalled")
                return
            }
            // Torrent: reset and keep recovering the same stream (fall through).
            Log.w(TAG, "VOD recovery cycling — continuing torrent recovery (reason=$reason)")
        }

        vodRecoveryAttempt += 1
        val attempt = vodRecoveryAttempt
        val backoffMs = when (attempt) {
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            else -> 3_000L
        }

        _uiState.update {
            it.copy(isReconnecting = true, reconnectStatus = "Reconnecting… ($attempt/$vodMaxRetries)")
        }
        Log.w(TAG, "VOD recover #$attempt (reason=$reason, backoff=${backoffMs}ms, pos=$resumePos)")

        viewModelScope.launch {
            delay(backoffMs)
            val cur = player ?: return@launch
            if (cur !== exo) return@launch
            if (_uiState.value.isIptv) return@launch

            // Reset trackers before touching the player so a racing watchdog tick
            // can't fire another recovery on stale data.
            vodBufferingSinceMs = 0L
            vodLastPositionMs = -1L
            vodLastPositionAt = System.currentTimeMillis()
            vodHealthyStreakStartMs = 0L

            try {
                if (attempt <= 2) {
                    // Tier 1: re-prepare in place — position is retained by ExoPlayer.
                    Log.i(TAG, "VOD recover tier 1: re-prepare in place")
                    cur.prepare()
                    cur.playWhenReady = true
                } else {
                    // Tier 2: full re-prepare with a fresh MediaItem, resume via seek.
                    Log.i(TAG, "VOD recover tier 2: full re-prepare @ $resumePos")
                    if (resumePos > 5_000L) pendingSeekMs = resumePos
                    cur.stop()
                    cur.clearMediaItems()
                    cur.setMediaItem(MediaItem.fromUri(url))
                    cur.prepare()
                    cur.playWhenReady = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "VOD recover attempt $attempt threw: ${e.message}")
            }
        }
    }

    fun showSourcesPanel() {
        _uiState.update { it.copy(showSourcesPanel = true, showControls = false) }
        val s = _uiState.value
        // Fetch the addon streams for THIS item on first open — for addon-origin
        // playback AND for TMDB titles (via their IMDB id), so the panel always
        // shows addon alternatives alongside the app sources.
        if (!s.isIptv && s.animeEmbeds == null && s.addonStreams.isEmpty() && !s.isLoadingAddonStreams) {
            if (resumeStremioId == null && s.tmdbId <= 0) return
            _uiState.update { it.copy(isLoadingAddonStreams = true) }
            viewModelScope.launch {
                try {
                    val sid = resumeStremioId ?: runCatching {
                        if (s.isMovie) TmdbClient.api.getMovieExternalIds(s.tmdbId, TmdbClient.API_KEY).imdbId
                        else TmdbClient.api.getTvExternalIds(s.tmdbId, TmdbClient.API_KEY).imdbId
                    }.getOrNull()?.takeIf { it.startsWith("tt") }
                    if (sid.isNullOrBlank()) {
                        _uiState.update { it.copy(isLoadingAddonStreams = false) }
                        return@launch
                    }
                    val addons = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
                    // For series, streams are keyed by videoId (id:season:episode).
                    val candidateIds = buildList {
                        if (!s.isMovie && s.seasonNumber != null && s.episodeNumber != null &&
                            !sid.contains(":")
                        ) add("$sid:${s.seasonNumber}:${s.episodeNumber}")
                        add(sid)
                    }
                    val candidateTypes = listOf(
                        resumeStremioType ?: if (s.isMovie) "movie" else "series",
                        "movie", "series", "channel", "tv"
                    ).distinct()
                    var streams: List<com.playtorrio.tv.data.stremio.StremioStream> = emptyList()
                    outer@ for (id in candidateIds) {
                        for (t in candidateTypes) {
                            streams = com.playtorrio.tv.data.stremio.StremioService.getStreams(
                                addons, t, id, resumeAddonId,
                                onPartial = { partial ->
                                    // Populate the panel as each addon answers.
                                    _uiState.update { it.copy(addonStreams = partial) }
                                }
                            )
                            if (streams.isNotEmpty()) break@outer
                        }
                    }
                    _uiState.update { it.copy(addonStreams = streams, isLoadingAddonStreams = false) }
                } catch (e: Exception) {
                    Log.w(TAG, "Addon stream list fetch failed: ${e.message}")
                    _uiState.update { it.copy(isLoadingAddonStreams = false) }
                }
            }
        }
    }

    /** Switch the CURRENT item to another addon stream, in place, resuming at
     *  the current position. */
    fun pickAddonStream(stream: com.playtorrio.tv.data.stremio.StremioStream) {
        val s = _uiState.value
        if (s.isSwitchingSource || s.isSwitchingEpisode) return
        resumeStreamPickKey = stream.url ?: stream.infoHash
        resumeStreamPickName = stream.name ?: stream.title
        stream.addonId?.takeIf { it.isNotBlank() }?.let { resumeAddonId = it }
        val resumePos = player?.currentPosition?.takeIf { it > 5_000L }
        _uiState.update {
            it.copy(
                showSourcesPanel = false, isSwitchingSource = true,
                currentStreamPickKey = resumeStreamPickKey,
                // The user explicitly picked an addon stream — playback is
                // addon-origin from here on (no extractor auto-fallback).
                isAddonOrigin = true,
            )
        }
        viewModelScope.launch {
            try {
                when (val route = com.playtorrio.tv.data.stremio.StremioService.routeStream(stream)) {
                    is com.playtorrio.tv.data.stremio.StreamRoute.DirectUrl -> {
                        teardownForSwap()
                        currentMagnetUri = null
                        val referer = route.headers?.get("Referer") ?: route.headers?.get("referer") ?: ""
                        customStreamHeaders = route.headers ?: emptyMap()
                        withContext(Dispatchers.Main) {
                            addedExternalSubConfigs.clear(); addedExternalSubLabels.clear()
                            player?.release(); player = null
                            pendingSeekMs = resumePos
                            _uiState.update { it.copy(isSwitchingSource = false, isStreamingMode = true, isMagnet = false, torrentHash = null) }
                            if (referer.isNotBlank()) createStreamingPlayer(route.url, referer) else createPlayer(route.url)
                        }
                    }
                    is com.playtorrio.tv.data.stremio.StreamRoute.Torrent -> {
                        teardownForSwap()
                        currentMagnetUri = route.magnet
                        resumeFileIdx = route.fileIdx
                        val context = getApplication<Application>()
                        com.playtorrio.tv.data.torrent.TorrServerService.ensureInitialized(context)
                        val result = com.playtorrio.tv.data.torrent.TorrServerService.startStreaming(
                            context = context, magnetUri = route.magnet,
                            seasonNumber = s.seasonNumber, episodeNumber = s.episodeNumber,
                            fileIdx = route.fileIdx,
                        )
                        withContext(Dispatchers.Main) {
                            addedExternalSubConfigs.clear(); addedExternalSubLabels.clear()
                            player?.release(); player = null
                            pendingSeekMs = resumePos
                            _uiState.update { it.copy(isSwitchingSource = false, isStreamingMode = false, isMagnet = true, torrentHash = result.hash) }
                            startStatsPoller(result.hash)
                            createPlayer(result.url)
                        }
                    }
                    else -> {
                        _uiState.update { it.copy(isSwitchingSource = false, error = "Unsupported stream type") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pickAddonStream failed", e)
                _uiState.update { it.copy(isSwitchingSource = false, error = e.message ?: "Stream switch failed") }
            }
        }
    }

    fun dismissSourcesPanel() {
        _uiState.update { it.copy(showSourcesPanel = false) }
        showControls()
    }

    fun switchAnimeSource(embed: com.playtorrio.tv.data.anime.AnimeEmbed) {
        val state = _uiState.value
        Log.i(TAG, "switchAnimeSource(${embed.server})")
        _uiState.update { it.copy(showSourcesPanel = false, isSwitchingSource = true) }

        viewModelScope.launch {
            val result = runCatching { com.playtorrio.tv.data.anime.AnimeService.extractDirect(embed) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (result != null) {
                    player?.release()
                    player = null
                    _uiState.update {
                        it.copy(
                            isSwitchingSource = false,
                            isConnecting = true,
                            currentAnimeServer = embed.server,
                            currentAnimeEmbedUrl = embed.url,
                            connectionStatus = "Loading ${embed.server}…"
                        )
                    }

                    val tracksJson = org.json.JSONArray().apply {
                        result.tracks.forEach { track ->
                            val obj = org.json.JSONObject()
                            obj.put("url", track.url)
                            obj.put("label", track.label)
                            obj.put("isDefault", track.isDefault)
                            put(obj)
                        }
                    }.toString()

                    createStreamingPlayer(
                        streamUrl = result.url,
                        referer = result.referer,
                        animeTracksJson = tracksJson,
                        animeOrigin = result.origin
                    )
                } else {
                    _uiState.update { it.copy(isSwitchingSource = false) }
                }
            }
        }
    }

    /** From the error overlay: retry playback without leaving the player. */
    fun retryFromError() {
        _uiState.update { it.copy(error = null, isConnecting = true, connectionStatus = "Retrying…") }
        val s = _uiState.value
        val url = currentStreamUrl
        when {
            // Extractor streaming with a TMDB id: re-run extraction for the
            // current source. Addon-origin retries its own URL below instead.
            s.isStreamingMode && !s.isIptv && s.tmdbId > 0 && !s.isAddonOrigin -> switchToSource(s.currentSourceIndex)
            // Otherwise re-prepare whatever URL we were playing.
            url != null -> viewModelScope.launch(Dispatchers.Main) {
                try {
                    val exo = player
                    if (exo != null) {
                        exo.stop(); exo.clearMediaItems()
                        exo.setMediaItem(MediaItem.fromUri(url)); exo.prepare(); exo.playWhenReady = true
                    } else if (s.isStreamingMode) {
                        createStreamingPlayer(url, s.referer ?: "")
                    } else {
                        createPlayer(url)
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isConnecting = false, error = e.message ?: "Retry failed") }
                }
            }
            else -> _uiState.update { it.copy(isConnecting = false, error = "Nothing to retry — try changing source") }
        }
    }

    /** From the error overlay: clear the error and open the source picker. */
    fun changeSourceFromError() {
        _uiState.update { it.copy(error = null, isConnecting = false) }
        showSourcesPanel()
    }

    fun switchToSource(sourceIdx: Int, userInitiated: Boolean = false) {
        val state = _uiState.value
        Log.i(TAG, "switchToSource($sourceIdx, user=$userInitiated) tmdbId=${state.tmdbId} season=${state.seasonNumber} episode=${state.episodeNumber} isMovie=${state.isMovie}")
        // A manual pick locks the source: stop auto-advancing to other sources
        // on failure and just keep (re)trying this one until the user changes it.
        if (userInitiated) {
            autoAdvanceSources = false
            failedSourceIndices.clear()
            streamRetryJob?.cancel()
            resetStartupRetryState()
        }
        setSourceStatus(sourceIdx, "loading")
        _uiState.update { it.copy(showSourcesPanel = false, isSwitchingSource = true) }

        viewModelScope.launch {
            val context = getApplication<Application>()
            // Manual picks get a generous floor on the timeout — a short user
            // setting shouldn't make an explicit choice fail instantly.
            val baseTimeout = AppPreferences.streamingExtractTimeoutSec * 1000L
            val result = StreamExtractorService.extract(
                context = context,
                sourceIdx = sourceIdx,
                tmdbId = state.tmdbId,
                season = if (state.isMovie) null else state.seasonNumber,
                episode = if (state.isMovie) null else state.episodeNumber,
                timeoutMs = if (userInitiated) maxOf(baseTimeout, 30_000L) else baseTimeout
            )
            if (result != null) {
                withContext(Dispatchers.Main) {
                    customStreamHeaders = emptyMap() // extractor stream — default headers
                    player?.release()
                    player = null
                    _uiState.update {
                        it.copy(
                            isSwitchingSource = false,
                            currentSourceIndex = sourceIdx,
                            isConnecting = true,
                            // An extractor source is now playing — no longer addon-origin.
                            isAddonOrigin = false,
                            currentStreamPickKey = null,
                            connectionStatus = "Loading ${StreamExtractorService.SOURCES.find { it.index == sourceIdx }?.name ?: "source"}…"
                        )
                    }
                    createStreamingPlayer(result.url, result.referer)
                }
            } else if (userInitiated) {
                // The user's manual pick failed to extract. Do NOT jump to another
                // source (that felt like "it always goes back to the default") —
                // keep the currently-playing stream, tag the pick as failed, and
                // tell the user.
                Log.w(TAG, "Manual source pick $sourceIdx failed extraction — keeping current stream")
                setSourceStatus(sourceIdx, "failed")
                autoAdvanceSources = true // the pick never took effect
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isSwitchingSource = false, isConnecting = false) }
                    val name = StreamExtractorService.SOURCES.find { it.index == sourceIdx }?.name ?: "Source"
                    android.widget.Toast.makeText(
                        getApplication(),
                        "$name has no stream for this title — keeping current source",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // Extraction failed for this source — fall back to the next one in priority order.
                Log.w(TAG, "Extraction returned null for source $sourceIdx, falling back")
                failedSourceIndices.add(sourceIdx)
                setSourceStatus(sourceIdx, "failed")
                val nextIdx = orderedSourceIndices.firstOrNull { it !in failedSourceIndices }
                if (nextIdx != null) {
                    val nextName = StreamExtractorService.SOURCES.find { it.index == nextIdx }?.name ?: "source"
                    val failedName = StreamExtractorService.SOURCES.find { it.index == sourceIdx }?.name ?: "source"
                    Log.i(TAG, "Falling back from $failedName to $nextName")
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isSwitchingSource = false,
                                isConnecting = true,
                                connectionStatus = "$failedName failed, trying $nextName…"
                            )
                        }
                        switchToSource(nextIdx)
                    }
                } else {
                    // Never give up — cycle the whole list again after a pause.
                    Log.w(TAG, "All sources exhausted during extraction — cycling from the top")
                    failedSourceIndices.clear()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isSwitchingSource = false,
                                isConnecting = true,
                                isReconnecting = true,
                                reconnectStatus = "Reconnecting…",
                                error = null
                            )
                        }
                        streamRetryJob?.cancel()
                        streamRetryJob = viewModelScope.launch {
                            delay(4_000)
                            orderedSourceIndices.firstOrNull()?.let { switchToSource(it) }
                        }
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Episodes panel + in-place episode switching
    // ════════════════════════════════════════════════════════════════════════

    fun showEpisodesPanel() {
        val s = _uiState.value
        if (s.isMovie) return
        _uiState.update { it.copy(showEpisodesPanel = true, showControls = false) }
        if (s.episodes.isEmpty() && !s.isLoadingEpisodes) {
            loadEpisodesForCurrentSeries()
        }
    }

    fun dismissEpisodesPanel() {
        _uiState.update { it.copy(showEpisodesPanel = false) }
        showControls()
    }

    fun loadEpisodesForCurrentSeries() {
        val s = _uiState.value
        if (s.isLoadingEpisodes) return

        // Anime episodes come from AniList (Anikoto), not TMDB.
        val animeId = resumeAnimeId?.toIntOrNull()
        if (animeId != null && s.tmdbId <= 0) {
            _uiState.update { it.copy(isLoadingEpisodes = true) }
            viewModelScope.launch {
                try {
                    val anime = com.playtorrio.tv.data.anime.AnimeService.getDetails(animeId)
                    val eps = com.playtorrio.tv.data.anime.AnimeService.getEpisodes(anime).map { ae ->
                        com.playtorrio.tv.data.model.Episode(
                            id = ae.number,
                            episodeNumber = ae.number,
                            name = ae.title,
                            stillPath = null,
                            seasonNumber = 1,
                        )
                    }
                    _uiState.update { it.copy(episodes = eps, isLoadingEpisodes = false) }
                    computeNextEpisode()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load anime episodes", e)
                    _uiState.update { it.copy(isLoadingEpisodes = false) }
                }
            }
            return
        }

        // Addon series (no TMDB id): episode list comes from the addon's meta —
        // exactly what the addon detail page shows.
        val seriesId = resumeStremioSeriesId
        if (s.tmdbId <= 0 && seriesId != null && !s.isMovie) {
            _uiState.update { it.copy(isLoadingEpisodes = true) }
            viewModelScope.launch {
                try {
                    val addons = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
                    val meta = com.playtorrio.tv.data.stremio.StremioService.getMeta(
                        addons = addons,
                        type = resumeStremioType ?: "series",
                        id = seriesId,
                        preferredAddonId = resumeAddonId
                    )
                    val curSeason = s.seasonNumber
                    val eps = meta?.videos.orEmpty()
                        .filter { v -> v.episode != null && (curSeason == null || v.season == null || v.season == curSeason) }
                        .sortedBy { it.episode }
                        .map { v ->
                            com.playtorrio.tv.data.model.Episode(
                                id = v.episode!!,
                                episodeNumber = v.episode,
                                name = v.title,
                                overview = v.overview,
                                stillPath = null,
                                seasonNumber = v.season ?: curSeason ?: 1,
                            )
                        }
                    _uiState.update { it.copy(episodes = eps, isLoadingEpisodes = false) }
                    computeNextEpisode()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load addon episodes", e)
                    _uiState.update { it.copy(isLoadingEpisodes = false) }
                }
            }
            return
        }

        val tvId = s.tmdbId.takeIf { it > 0 } ?: return
        val season = s.seasonNumber ?: return
        _uiState.update { it.copy(isLoadingEpisodes = true) }
        viewModelScope.launch {
            try {
                val seasonData = TmdbClient.api.getTvSeason(tvId, season, TmdbClient.API_KEY)
                val eps = (seasonData.episodes ?: emptyList()).map {
                    if (it.seasonNumber == null) it.copy(seasonNumber = season) else it
                }
                _uiState.update { it.copy(episodes = eps, isLoadingEpisodes = false) }
                computeNextEpisode()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load season episodes", e)
                _uiState.update { it.copy(isLoadingEpisodes = false) }
            }
        }
    }

    private fun computeNextEpisode() {
        // Re-arm the Up Next overlay for the (possibly new) current episode.
        resetUpNextForNewItem()
        val s = _uiState.value
        val curEp = s.episodeNumber ?: return
        val next = s.episodes.firstOrNull { it.episodeNumber == curEp + 1 }
        _uiState.update { it.copy(nextEpisode = next) }
    }

    /** User picked an episode in the panel. Branches by playback mode/origin. */
    fun pickEpisode(episode: com.playtorrio.tv.data.model.Episode) {
        val s = _uiState.value
        if (s.isSwitchingEpisode) return
        // Skip no-op
        if (episode.seasonNumber == s.seasonNumber && episode.episodeNumber == s.episodeNumber) {
            dismissEpisodesPanel()
            return
        }

        // Anime: resolve the episode via AniList sources (no TMDB extraction).
        if (resumeAnimeId != null) {
            switchToAnimeEpisode(episode.episodeNumber)
            return
        }

        if (s.isStreamingMode) {
            switchToStreamingEpisode(episode)
            return
        }

        // Non-streaming: open source picker overlay matching original origin
        when {
            resumeAddonId != null && resumeStremioType != null -> {
                openEpisodeSourceOverlay(episode, EpisodeOverlayKind.ADDON_STREAM)
            }
            currentMagnetUri != null -> {
                openEpisodeSourceOverlay(episode, EpisodeOverlayKind.TORRENT)
            }
            else -> {
                // Default to torrent search
                openEpisodeSourceOverlay(episode, EpisodeOverlayKind.TORRENT)
            }
        }
    }

    fun playNextEpisode() {
        val ep = _uiState.value.nextEpisode ?: return
        pickEpisode(ep)
    }

    private fun openEpisodeSourceOverlay(
        episode: com.playtorrio.tv.data.model.Episode,
        kind: EpisodeOverlayKind
    ) {
        _uiState.update {
            it.copy(
                showEpisodesPanel = false,
                showEpisodeSourceOverlay = true,
                episodeOverlayKind = kind,
                pendingEpisode = episode,
                episodeOverlayTorrents = emptyList(),
                episodeOverlayStreams = emptyList(),
                isLoadingEpisodeOverlay = true,
            )
        }
        viewModelScope.launch {
            try {
                when (kind) {
                    EpisodeOverlayKind.TORRENT -> {
                        val s = _uiState.value
                        val sNum = episode.seasonNumber ?: s.seasonNumber
                        val results = com.playtorrio.tv.data.torrent.TorrentSearchService.search(
                            com.playtorrio.tv.data.torrent.TorrentSearchRequest(
                                title = s.title,
                                seasonNumber = sNum,
                                episodeNumber = episode.episodeNumber,
                                isMovie = false
                            )
                        )
                        _uiState.update {
                            it.copy(episodeOverlayTorrents = results, isLoadingEpisodeOverlay = false)
                        }
                    }
                    EpisodeOverlayKind.ADDON_STREAM -> {
                        // Prefer imdb; fall back to the addon's own series id
                        // (works for custom ids like kitsu:xxxx too).
                        val baseId = resumeImdbId ?: ensureImdbIdForCurrent() ?: resumeStremioSeriesId
                        if (baseId == null) {
                            _uiState.update { it.copy(isLoadingEpisodeOverlay = false) }
                            return@launch
                        }
                        val sNum = episode.seasonNumber ?: _uiState.value.seasonNumber
                        val videoId = "$baseId:${sNum}:${episode.episodeNumber}"
                        val addons = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
                        val streams = com.playtorrio.tv.data.stremio.StremioService.getStreams(
                            addons = addons,
                            type = "series",
                            id = videoId,
                            preferredAddonId = resumeAddonId
                        )
                        // If we know the original addon, prioritize its streams to top
                        val sorted = if (resumeAddonId != null) {
                            streams.sortedByDescending { it.addonId == resumeAddonId }
                        } else streams
                        _uiState.update {
                            it.copy(episodeOverlayStreams = sorted, isLoadingEpisodeOverlay = false)
                        }
                    }
                    EpisodeOverlayKind.NONE -> {
                        _uiState.update { it.copy(isLoadingEpisodeOverlay = false) }
                    }
                }
                // Autoplay path: auto-pick the top source so playback continues
                // without the user having to choose in the overlay.
                if (pendingAutoPickEpisode) {
                    pendingAutoPickEpisode = false
                    val st = _uiState.value
                    when (kind) {
                        EpisodeOverlayKind.TORRENT ->
                            st.episodeOverlayTorrents.firstOrNull()?.let { pickEpisodeTorrent(it) }
                        EpisodeOverlayKind.ADDON_STREAM ->
                            st.episodeOverlayStreams.firstOrNull()?.let { pickEpisodeStremioStream(it) }
                        EpisodeOverlayKind.NONE -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Episode source overlay load failed", e)
                pendingAutoPickEpisode = false
                _uiState.update { it.copy(isLoadingEpisodeOverlay = false) }
            }
        }
    }

    private suspend fun ensureImdbIdForCurrent(): String? {
        val s = _uiState.value
        val tmdbId = s.tmdbId.takeIf { it > 0 } ?: return null
        return runCatching {
            TmdbClient.api.getTvExternalIds(tmdbId, TmdbClient.API_KEY).imdbId
        }.getOrNull()?.takeIf { it.startsWith("tt") }
    }

    fun dismissEpisodeSourceOverlay() {
        _uiState.update {
            it.copy(
                showEpisodeSourceOverlay = false,
                pendingEpisode = null,
                episodeOverlayTorrents = emptyList(),
                episodeOverlayStreams = emptyList(),
                isLoadingEpisodeOverlay = false,
            )
        }
        showControls()
    }

    fun pickEpisodeTorrent(torrent: com.playtorrio.tv.data.torrent.TorrentResult) {
        val ep = _uiState.value.pendingEpisode ?: return
        switchToMagnetEpisode(torrent.magnetLink, fileIdx = null, episode = ep)
    }

    fun pickEpisodeStremioStream(stream: com.playtorrio.tv.data.stremio.StremioStream) {
        val ep = _uiState.value.pendingEpisode ?: return
        when (val route = com.playtorrio.tv.data.stremio.StremioService.routeStream(stream)) {
            is com.playtorrio.tv.data.stremio.StreamRoute.DirectUrl -> {
                switchToDirectUrlEpisode(route.url, route.headers, episode = ep, stream = stream)
            }
            is com.playtorrio.tv.data.stremio.StreamRoute.Torrent -> {
                switchToMagnetEpisode(route.magnet, route.fileIdx, episode = ep, stream = stream)
            }
            else -> {
                Log.w(TAG, "Unsupported stream route for episode switch: $route")
                _uiState.update { it.copy(error = "Unsupported stream type") }
            }
        }
    }

    /** Streaming-mode switch: re-extract for new episode + swap player in-place. */
    private fun switchToStreamingEpisode(episode: com.playtorrio.tv.data.model.Episode) {
        pendingAutoPickEpisode = false // streaming path never uses the source overlay
        customStreamHeaders = emptyMap() // extractor streams use default headers
        val s = _uiState.value
        val tmdbId = s.tmdbId.takeIf { it > 0 } ?: return
        val sNum = episode.seasonNumber ?: s.seasonNumber ?: return
        val eNum = episode.episodeNumber
        flushProgressInternal()
        _uiState.update {
            it.copy(
                showEpisodesPanel = false,
                isSwitchingEpisode = true,
                isConnecting = true,
                connectionStatus = "Loading S${sNum}E${eNum}…",
                seasonNumber = sNum,
                episodeNumber = eNum,
                episodeTitle = episode.name,
                duration = 0L,
                currentPosition = 0L,
                activeSkipSegment = null,
                skipSegments = emptyList(),
                externalSubtitles = emptyList(),
                nextEpisode = null,
            )
        }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val result = com.playtorrio.tv.data.streaming.StreamExtractorService.extract(
                    context = context,
                    sourceIdx = s.currentSourceIndex,
                    tmdbId = tmdbId,
                    season = sNum,
                    episode = eNum,
                    timeoutMs = AppPreferences.streamingExtractTimeoutSec * 1000L,
                )
                if (result == null) {
                    _uiState.update {
                        it.copy(
                            isSwitchingEpisode = false,
                            isConnecting = false,
                            error = "Failed to extract stream for next episode"
                        )
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    addedExternalSubConfigs.clear()
                    addedExternalSubLabels.clear()
                    player?.release()
                    player = null
                    _uiState.update { it.copy(isSwitchingEpisode = false) }
                    createStreamingPlayer(result.url, result.referer)
                }
                refetchSubsAndSkipForEpisode(episode)
                computeNextEpisode()
            } catch (e: Exception) {
                Log.e(TAG, "switchToStreamingEpisode failed", e)
                _uiState.update {
                    it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message)
                }
            }
        }
    }

    private fun switchToDirectUrlEpisode(
        url: String,
        headers: Map<String, String>?,
        episode: com.playtorrio.tv.data.model.Episode,
        stream: com.playtorrio.tv.data.stremio.StremioStream,
    ) {
        val sNum = episode.seasonNumber ?: _uiState.value.seasonNumber ?: return
        val eNum = episode.episodeNumber
        customStreamHeaders = headers ?: emptyMap()
        flushProgressInternal()
        // Update resume context for new pick
        resumeStreamPickName = stream.name ?: stream.title
        resumeStreamPickKey = stream.url ?: stream.infoHash
        currentMagnetUri = null
        _uiState.update {
            it.copy(
                showEpisodeSourceOverlay = false,
                pendingEpisode = null,
                isSwitchingEpisode = true,
                isConnecting = true,
                connectionStatus = "Loading S${sNum}E${eNum}…",
                seasonNumber = sNum,
                episodeNumber = eNum,
                episodeTitle = episode.name,
                duration = 0L,
                currentPosition = 0L,
                activeSkipSegment = null,
                skipSegments = emptyList(),
                externalSubtitles = emptyList(),
                nextEpisode = null,
            )
        }
        viewModelScope.launch {
            try {
                // Tear down current torrent if any
                val oldHash = _uiState.value.torrentHash
                if (!oldHash.isNullOrBlank()) {
                    runCatching { com.playtorrio.tv.data.torrent.TorrServerService.removeTorrent(oldHash) }
                }
                withContext(Dispatchers.Main) {
                    addedExternalSubConfigs.clear()
                    addedExternalSubLabels.clear()
                    player?.release()
                    player = null
                    _uiState.update { it.copy(isSwitchingEpisode = false, torrentHash = null) }
                    val referer = headers?.get("Referer") ?: headers?.get("referer") ?: ""
                    if (referer.isNotBlank()) {
                        createStreamingPlayer(url, referer)
                    } else {
                        createPlayer(url)
                    }
                }
                refetchSubsAndSkipForEpisode(episode)
                computeNextEpisode()
            } catch (e: Exception) {
                Log.e(TAG, "switchToDirectUrlEpisode failed", e)
                _uiState.update {
                    it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message)
                }
            }
        }
    }

    private fun switchToMagnetEpisode(
        magnet: String,
        fileIdx: Int?,
        episode: com.playtorrio.tv.data.model.Episode,
        stream: com.playtorrio.tv.data.stremio.StremioStream? = null,
    ) {
        val sNum = episode.seasonNumber ?: _uiState.value.seasonNumber ?: return
        val eNum = episode.episodeNumber
        flushProgressInternal()
        currentMagnetUri = magnet
        resumeFileIdx = fileIdx
        if (stream != null) {
            resumeStreamPickName = stream.name ?: stream.title
            resumeStreamPickKey = stream.infoHash ?: stream.url
        }
        _uiState.update {
            it.copy(
                showEpisodeSourceOverlay = false,
                pendingEpisode = null,
                isSwitchingEpisode = true,
                isConnecting = true,
                connectionStatus = "Switching torrent…",
                seasonNumber = sNum,
                episodeNumber = eNum,
                episodeTitle = episode.name,
                duration = 0L,
                currentPosition = 0L,
                activeSkipSegment = null,
                skipSegments = emptyList(),
                externalSubtitles = emptyList(),
                nextEpisode = null,
            )
        }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                // 1. Tear down old torrent
                val oldHash = _uiState.value.torrentHash
                if (!oldHash.isNullOrBlank()) {
                    runCatching { com.playtorrio.tv.data.torrent.TorrServerService.removeTorrent(oldHash) }
                }
                statsJob?.cancel()
                // 2. Resolve new
                if (AppPreferences.debridEnabled) {
                    _uiState.update { it.copy(connectionStatus = "Resolving via debrid…") }
                    val debridUrl = com.playtorrio.tv.data.debrid.DebridResolver.resolve(magnet, isMovie = false, season = sNum, episode = eNum)
                        ?: throw IllegalStateException("Debrid could not resolve this torrent")
                    withContext(Dispatchers.Main) {
                        addedExternalSubConfigs.clear()
                        addedExternalSubLabels.clear()
                        player?.release()
                        player = null
                        _uiState.update { it.copy(isSwitchingEpisode = false, torrentHash = null) }
                        createPlayer(debridUrl)
                    }
                } else {
                    _uiState.update { it.copy(connectionStatus = "Adding torrent…") }
                    com.playtorrio.tv.data.torrent.TorrServerService.ensureInitialized(context)
                    val result = com.playtorrio.tv.data.torrent.TorrServerService.startStreaming(
                        context = context,
                        magnetUri = magnet,
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        fileIdx = fileIdx,
                    )
                    withContext(Dispatchers.Main) {
                        addedExternalSubConfigs.clear()
                        addedExternalSubLabels.clear()
                        player?.release()
                        player = null
                        _uiState.update {
                            it.copy(
                                isSwitchingEpisode = false,
                                torrentHash = result.hash,
                            )
                        }
                        startStatsPoller(result.hash)
                        createPlayer(result.url)
                    }
                }
                refetchSubsAndSkipForEpisode(episode)
                computeNextEpisode()
            } catch (e: Exception) {
                Log.e(TAG, "switchToMagnetEpisode failed", e)
                _uiState.update {
                    it.copy(isSwitchingEpisode = false, isConnecting = false, error = e.message ?: "Switch failed")
                }
            }
        }
    }

    private suspend fun refetchSubsAndSkipForEpisode(episode: com.playtorrio.tv.data.model.Episode) {
        val s = _uiState.value
        val tmdbId = s.tmdbId.takeIf { it > 0 } ?: return
        val sNum = episode.seasonNumber ?: s.seasonNumber ?: return
        val eNum = episode.episodeNumber
        coroutineScope {
            launch {
                runCatching {
                    val subs = com.playtorrio.tv.data.subtitle.SubtitleService.fetchSubtitles(
                        tmdbId = tmdbId,
                        season = sNum,
                        episode = eNum,
                        title = s.title.takeIf { it.isNotBlank() },
                        year = s.year?.toIntOrNull(),
                    )
                    _uiState.update { it.copy(externalSubtitles = subs) }
                    withContext(Dispatchers.Main) { updateSubtitleTrackList() }
                    fetchStremioSubtitles(tmdbId, false, sNum, eNum)
                }
            }
            launch {
                runCatching {
                    val segs = com.playtorrio.tv.data.skip.SkipSegmentService.fetchSegments(
                        tmdbId, false, sNum, eNum
                    )
                    _uiState.update { it.copy(skipSegments = segs) }
                }
            }
        }
    }

    /** Synchronous best-effort flush for episode switch. */
    private fun flushProgressInternal() {
        runCatching { flushProgress() }
    }
}
