package com.playtorrio.tv.data.stremio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Manages the list of installed Stremio addons.
 * Persists to SharedPreferences as a JSON array of InstalledAddon.
 * Thread-safe via coroutine dispatchers.
 */
object StremioAddonRepository {

    private const val PREFS_BASE = "stremio_prefs"
    private const val KEY_ADDONS = "installed_addons"
    private const val KEY_DEFAULTS_SEEDED = "defaults_seeded_v2"
    private const val TAG = "StremioAddonRepo"

    data class RecommendedAddon(
        val name: String,
        val description: String,
        val manifestUrl: String,
    )

    /**
     * Curated torrent/debrid aggregator addons. All work without configuration
     * (public instances). Torrentio aggregates YTS, EZTV, 1337x, ThePirateBay,
     * RARBG mirrors, TorrentGalaxy, MagnetDL, Nyaa (anime), etc., and honors
     * Real-Debrid/TorBox. These surface as stream options on every TMDB title.
     */
    val RECOMMENDED_ADDONS = listOf(
        RecommendedAddon(
            "Cinemeta",
            "Official catalogs — Popular & Featured movies and series (browsable)",
            "https://v3-cinemeta.strem.io/manifest.json",
        ),
        RecommendedAddon(
            "Torrentio",
            "Torrents from YTS, EZTV, 1337x, TPB, RARBG, TorrentGalaxy, Nyaa… (debrid-ready)",
            "https://torrentio.strem.fun/manifest.json",
        ),
        RecommendedAddon(
            "Anime Kitsu",
            "Anime catalogs & metadata from Kitsu (browsable)",
            "https://anime-kitsu.strem.fun/manifest.json",
        ),
        RecommendedAddon(
            "KnightCrawler",
            "Community torrent aggregator (debrid-ready)",
            "https://knightcrawler.elfhosted.com/manifest.json",
        ),
        RecommendedAddon(
            "OpenSubtitles v3",
            "Subtitles for almost everything, many languages",
            "https://opensubtitles-v3.strem.io/manifest.json",
        ),
        RecommendedAddon(
            "Comet",
            "Debrid-aware torrent/stream aggregator",
            "https://comet.elfhosted.com/manifest.json",
        ),
        RecommendedAddon(
            "USA TV",
            "Live US TV channels catalog (browsable)",
            "https://848b3516657c-usatv.baby-beamup.club/manifest.json",
        ),
    )

    /** Manifest URLs auto-installed on first run — a mix of browsable catalogs
     *  (Cinemeta, Anime Kitsu), torrent streams (Torrentio, KnightCrawler) and
     *  subtitles (OpenSubtitles) so everything works out of the box. */
    private val DEFAULT_MANIFESTS = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://torrentio.strem.fun/manifest.json",
        "https://anime-kitsu.strem.fun/manifest.json",
        "https://knightcrawler.elfhosted.com/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
    )

    fun isInstalled(manifestId: String): Boolean = getAddons().any { it.manifest.id == manifestId }

    private lateinit var prefs: SharedPreferences

    private val gson = GsonBuilder()
        .registerTypeAdapter(ResourceDescriptor::class.java, ResourceDescriptorDeserializer())
        .create()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        val activeId = com.playtorrio.tv.data.profile.ProfileManager.activeId()
        val fileName = if (activeId == "default") PREFS_BASE else "${PREFS_BASE}_$activeId"
        prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    fun getAddons(): List<InstalledAddon> {
        val json = prefs.getString(KEY_ADDONS, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<InstalledAddon>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize addons", e)
            emptyList()
        }
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Fetches and installs an addon by manifest URL.
     * @return Result.success(InstalledAddon) or Result.failure with an error message.
     */
    suspend fun installAddon(manifestUrl: String): Result<InstalledAddon> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = manifestUrl.trim()

                // Build transport URL by stripping /manifest.json
                val transportUrl = when {
                    cleanUrl.endsWith("/manifest.json") ->
                        cleanUrl.removeSuffix("/manifest.json")
                    else -> {
                        // Accept transport URL entered without /manifest.json
                        cleanUrl.trimEnd('/')
                    }
                }
                val fetchUrl = "$transportUrl/manifest.json"

                val req = Request.Builder().url(fetchUrl).build()
                val body = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("HTTP ${resp.code}: ${resp.message}")
                        )
                    }
                    resp.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))
                }

                val manifest = gson.fromJson(body, AddonManifest::class.java)
                    ?: return@withContext Result.failure(Exception("Failed to parse manifest"))

                if (manifest.id.isBlank()) {
                    return@withContext Result.failure(Exception("Invalid manifest: missing id"))
                }

                val addon = InstalledAddon(transportUrl = transportUrl, manifest = manifest)

                // Deduplicate by addon id, replacing existing if same id
                val current = getAddons().toMutableList()
                current.removeAll { it.manifest.id == manifest.id }
                current.add(addon)
                saveAddons(current)

                Result.success(addon)
            } catch (e: Exception) {
                Log.e(TAG, "Install failed for $manifestUrl", e)
                Result.failure(e)
            }
        }

    // ── Defaults / recommended ─────────────────────────────────────────────────

    /** Install any recommended addons not already present. Returns how many were
     *  newly installed. Failures (dead manifest) are skipped silently. */
    suspend fun installRecommended(): Int = withContext(Dispatchers.IO) {
        var added = 0
        val have = getAddons().map { it.manifest.id }.toMutableSet()
        for (rec in RECOMMENDED_ADDONS) {
            val result = installAddon(rec.manifestUrl)
            result.getOrNull()?.let { if (have.add(it.manifest.id)) added++ }
        }
        added
    }

    /** One-time: seed the default torrent addon on first launch per profile so
     *  torrents work without the user configuring anything. Safe to call every
     *  launch — it no-ops after the first success and never throws. */
    suspend fun seedDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_DEFAULTS_SEEDED, false)) return@withContext
        try {
            for (manifest in DEFAULT_MANIFESTS) {
                runCatching { installAddon(manifest) }
                    .onSuccess { if (it.isSuccess) Log.i(TAG, "Seeded default addon: $manifest") }
            }
            prefs.edit().putBoolean(KEY_DEFAULTS_SEEDED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "seedDefaults failed: ${e.message}")
        }
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    suspend fun removeAddon(addonId: String) = withContext(Dispatchers.IO) {
        val current = getAddons().toMutableList()
        current.removeAll { it.manifest.id == addonId }
        saveAddons(current)
    }

    // ── Persist ───────────────────────────────────────────────────────────────

    private fun saveAddons(addons: List<InstalledAddon>) {
        prefs.edit().putString(KEY_ADDONS, gson.toJson(addons)).apply()
    }
}
