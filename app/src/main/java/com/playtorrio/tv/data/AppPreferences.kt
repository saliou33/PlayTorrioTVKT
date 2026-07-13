package com.playtorrio.tv.data

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    private const val PREFS_BASE = "playtorrio_prefs"
    private const val KEY_STREAMING_MODE = "streaming_mode"
    private const val KEY_DEBRID_ENABLED = "debrid_enabled"
    private const val KEY_DEBRID_PROVIDER = "debrid_provider"
    private const val KEY_REALDEBRID_API_KEY = "realdebrid_api_key"
    private const val KEY_TORBOX_API_KEY = "torbox_api_key"
    private const val KEY_TORRENT_PRESET = "torrent_preset"
    private const val KEY_TORRENT_CACHE_MB = "torrent_cache_mb"
    private const val KEY_TORRENT_PRELOAD = "torrent_preload"
    private const val KEY_TORRENT_READ_AHEAD = "torrent_read_ahead"
    private const val KEY_TORRENT_CONNECTIONS = "torrent_connections"
    private const val KEY_TORRENT_RESPONSIVE = "torrent_responsive"
    private const val KEY_TORRENT_DISABLE_UPLOAD = "torrent_disable_upload"
    private const val KEY_TORRENT_DISABLE_IPV6 = "torrent_disable_ipv6"
    private const val KEY_TRAILER_AUTOPLAY = "trailer_autoplay"
    private const val KEY_TRAILER_DELAY_SEC = "trailer_delay_sec"
    private const val KEY_STREAMING_SOURCE_ORDER = "streaming_source_order"
    private const val KEY_STREAMING_SOURCE_ORDER_VERSION = "streaming_source_order_version"
    private const val KEY_STREAMING_EXTRACT_TIMEOUT_SEC = "streaming_extract_timeout_sec"
    // Bump when DEFAULT_STREAMING_SOURCE_ORDER changes meaningfully (e.g. a new
    // reliable source is added) so existing installs get re-seeded instead of
    // being stuck on a stale saved order that lacks the new source.
    private const val CURRENT_SOURCE_ORDER_VERSION = 2
    private const val KEY_SAVED_ALBUM_IDS = "saved_album_ids"
    private const val KEY_SAVED_TRACK_IDS = "saved_track_ids"
    private const val KEY_MUSIC_PLAYLISTS = "music_playlists"
    private const val KEY_SAVED_AUDIOBOOKS = "saved_audiobooks_v1"
    private const val KEY_AUDIOBOOK_PROGRESS = "audiobook_progress_v1"
    private const val KEY_WATCH_PROGRESS = "watch_progress_v1"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        val activeId = com.playtorrio.tv.data.profile.ProfileManager.activeId()
        val fileName = if (activeId == "default") PREFS_BASE else "${PREFS_BASE}_$activeId"
        prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        migrateSourceOrder()
        com.playtorrio.tv.data.stremio.StremioAddonRepository.init(context)
    }

    /** Re-seed a stale saved source order (e.g. one that predates the 111477
     *  source) to the current default so new reliable sources are actually
     *  prioritized after an update. */
    private fun migrateSourceOrder() {
        val v = prefs.getInt(KEY_STREAMING_SOURCE_ORDER_VERSION, 0)
        if (v < CURRENT_SOURCE_ORDER_VERSION) {
            prefs.edit()
                .remove(KEY_STREAMING_SOURCE_ORDER)
                .putInt(KEY_STREAMING_SOURCE_ORDER_VERSION, CURRENT_SOURCE_ORDER_VERSION)
                .apply()
        }
    }

    var streamingMode: Boolean
        get() = prefs.getBoolean(KEY_STREAMING_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_STREAMING_MODE, value).apply()

    var debridEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBRID_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBRID_ENABLED, value).apply()

    /** "realdebrid" or "torbox" */
    var debridProvider: String
        get() = prefs.getString(KEY_DEBRID_PROVIDER, "realdebrid") ?: "realdebrid"
        set(value) = prefs.edit().putString(KEY_DEBRID_PROVIDER, value).apply()

    var realDebridApiKey: String
        get() = prefs.getString(KEY_REALDEBRID_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REALDEBRID_API_KEY, value).apply()

    var torboxApiKey: String
        get() = prefs.getString(KEY_TORBOX_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TORBOX_API_KEY, value).apply()

    var torrentPreset: String
        get() = prefs.getString(KEY_TORRENT_PRESET, "balanced") ?: "balanced"
        set(value) = prefs.edit().putString(KEY_TORRENT_PRESET, value).apply()

    var torrentCacheSizeMb: Int
        get() = prefs.getInt(KEY_TORRENT_CACHE_MB, 256)
        set(value) = prefs.edit().putInt(KEY_TORRENT_CACHE_MB, value).apply()

    var torrentPreloadPercent: Int
        get() = prefs.getInt(KEY_TORRENT_PRELOAD, 1)
        set(value) = prefs.edit().putInt(KEY_TORRENT_PRELOAD, value).apply()

    var torrentReadAheadPercent: Int
        get() = prefs.getInt(KEY_TORRENT_READ_AHEAD, 86)
        set(value) = prefs.edit().putInt(KEY_TORRENT_READ_AHEAD, value).apply()

    var torrentConnectionsLimit: Int
        get() = prefs.getInt(KEY_TORRENT_CONNECTIONS, 140)
        set(value) = prefs.edit().putInt(KEY_TORRENT_CONNECTIONS, value).apply()

    var torrentResponsiveMode: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_RESPONSIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_TORRENT_RESPONSIVE, value).apply()

    var torrentDisableUpload: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_DISABLE_UPLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_TORRENT_DISABLE_UPLOAD, value).apply()

    var torrentDisableIpv6: Boolean
        get() = prefs.getBoolean(KEY_TORRENT_DISABLE_IPV6, true)
        set(value) = prefs.edit().putBoolean(KEY_TORRENT_DISABLE_IPV6, value).apply()

    var trailerAutoplay: Boolean
        get() = prefs.getBoolean(KEY_TRAILER_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_TRAILER_AUTOPLAY, value).apply()

    /** Seconds to wait before auto-playing trailer (3–10) */
    var trailerDelaySec: Int
        get() = prefs.getInt(KEY_TRAILER_DELAY_SEC, 3)
        set(value) = prefs.edit().putInt(KEY_TRAILER_DELAY_SEC, value.coerceIn(3, 10)).apply()

    /**
     * Ordered list of streaming source indices (highest priority first).
     * Defaults to: Videasy, VsEmbed, Vidlink, 111Movies, RgShows, 4KHDHub, HDHub4u, FlixerTV.
     * Sources missing from this list will be appended in their default order at runtime.
     */
    var streamingSourceOrder: List<Int>
        get() {
            val raw = prefs.getString(KEY_STREAMING_SOURCE_ORDER, null)
            if (raw.isNullOrBlank()) return DEFAULT_STREAMING_SOURCE_ORDER
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                .ifEmpty { DEFAULT_STREAMING_SOURCE_ORDER }
        }
        set(value) {
            val csv = value.joinToString(",")
            prefs.edit().putString(KEY_STREAMING_SOURCE_ORDER, csv).apply()
        }

    /** Per-source extraction timeout in seconds (5–60). Kept short so a slow or
     *  dead source is abandoned quickly instead of freezing the splash. */
    var streamingExtractTimeoutSec: Int
        get() = prefs.getInt(KEY_STREAMING_EXTRACT_TIMEOUT_SEC, 10)
        set(value) = prefs.edit().putInt(KEY_STREAMING_EXTRACT_TIMEOUT_SEC, value.coerceIn(5, 60)).apply()

    // Mobile-style priority: 111477 direct file index first (most reliable),
    // then the TMDB-embed players, then the HTTP scrapers.
    // 12=111477 3=Vidlink 10=VixSrc 11=VidNest 2=Videasy 1=111Movies
    // 8=VsEmbed 4=RgShows 5=4KHDHub 6=HDHub4u 7=FlixerTV 9=Xpass
    val DEFAULT_STREAMING_SOURCE_ORDER = listOf(12, 3, 10, 11, 2, 1, 8, 4, 5, 6, 7, 9)

    var savedAlbumIds: Set<String>
        get() = prefs.getStringSet(KEY_SAVED_ALBUM_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SAVED_ALBUM_IDS, value).apply()

    var savedTrackIds: Set<String>
        get() = prefs.getStringSet(KEY_SAVED_TRACK_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SAVED_TRACK_IDS, value).apply()

    /** JSON-encoded list of playlists: [{"name":"x","trackIds":["1","2"]}] */
    var musicPlaylists: String
        get() = prefs.getString(KEY_MUSIC_PLAYLISTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_MUSIC_PLAYLISTS, value).apply()

    /** JSON-encoded list of saved AudiobookDetail objects (one entry per liked book). */
    var savedAudiobooks: String
        get() = prefs.getString(KEY_SAVED_AUDIOBOOKS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_SAVED_AUDIOBOOKS, value).apply()

    /** JSON-encoded list of AudiobookProgress entries (continue-listening). */
    var audiobookProgress: String
        get() = prefs.getString(KEY_AUDIOBOOK_PROGRESS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_AUDIOBOOK_PROGRESS, value).apply()

    /** JSON-encoded list of WatchProgress entries (continue-watching for movies/TV). */
    var watchProgress: String
        get() = prefs.getString(KEY_WATCH_PROGRESS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_WATCH_PROGRESS, value).apply()

    fun applyTorrentPreset(preset: String) {
        when (preset.lowercase()) {
            "safe" -> {
                torrentPreset = "safe"
                torrentCacheSizeMb = 128
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 78
                torrentConnectionsLimit = 70
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            "balanced" -> {
                torrentPreset = "balanced"
                torrentCacheSizeMb = 256
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 86
                torrentConnectionsLimit = 140
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            "turbo" -> {
                torrentPreset = "turbo"
                torrentCacheSizeMb = 384
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 92
                torrentConnectionsLimit = 260
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            "extreme" -> {
                torrentPreset = "extreme"
                torrentCacheSizeMb = 512
                torrentPreloadPercent = 1
                torrentReadAheadPercent = 94
                torrentConnectionsLimit = 400
                torrentResponsiveMode = true
                torrentDisableUpload = true
                torrentDisableIpv6 = true
            }
            else -> torrentPreset = "custom"
        }
    }
}
