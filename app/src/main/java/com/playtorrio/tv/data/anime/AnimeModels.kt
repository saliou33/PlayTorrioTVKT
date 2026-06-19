package com.playtorrio.tv.data.anime

// ── AnimeCard (AniList media) ─────────────────────────────────────────────────
data class AnimeCard(
    val id: Int,
    val titleEnglish: String,
    val titleRomaji: String,
    val titleNative: String,
    val coverLarge: String?,
    val coverExtraLarge: String?,
    val coverColor: String?,
    val bannerImage: String?,
    val format: String?,
    val status: String?,
    val episodes: Int?,
    val duration: Int?,
    val averageScore: Int?,
    val popularity: Int?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val nextAiringEpisode: Map<String, Int?>? = null,
    val seasonYear: Int?,
    val season: String?,
    val mainStudio: String?,
    val isAdult: Boolean = false,
    val streamingEpisodes: List<Map<String, String>> = emptyList(),
) {
    val displayTitle: String get() =
        titleEnglish.ifBlank { titleRomaji.ifBlank { titleNative } }

    val coverUrl: String get() = coverExtraLarge ?: coverLarge ?: ""
    val bannerOrCover: String get() = bannerImage ?: coverUrl

    val cleanDescription: String get() = (description ?: "")
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .trim()

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: Map<String, Any?>): AnimeCard {
            val title = (json["title"] as? Map<*, *>)?.let { it as Map<String, Any?> } ?: emptyMap()
            val cover = (json["coverImage"] as? Map<*, *>)?.let { it as Map<String, Any?> } ?: emptyMap()
            val nae = (json["nextAiringEpisode"] as? Map<*, *>)?.let { it as Map<String, Any?> }
            val studios = (json["studios"] as? Map<*, *>)
                ?.let { (it["nodes"] as? List<*>) } ?: emptyList<Any>()
            val studio = (studios.firstOrNull() as? Map<*, *>)?.get("name") as? String
            val streamEps = ((json["streamingEpisodes"] as? List<*>) ?: emptyList<Any>())
                .filterIsInstance<Map<*, *>>()
                .map { m ->
                    mapOf(
                        "title" to (m["title"] ?: "").toString(),
                        "thumbnail" to (m["thumbnail"] ?: "").toString(),
                        "url" to (m["url"] ?: "").toString(),
                        "site" to (m["site"] ?: "").toString(),
                    )
                }
            return AnimeCard(
                id = (json["id"] as? Number)?.toInt() ?: 0,
                titleEnglish = (title["english"] ?: "") as String,
                titleRomaji = (title["romaji"] ?: "") as String,
                titleNative = (title["native"] ?: "") as String,
                coverLarge = cover["large"] as? String,
                coverExtraLarge = cover["extraLarge"] as? String,
                coverColor = cover["color"] as? String,
                bannerImage = json["bannerImage"] as? String,
                format = json["format"] as? String,
                status = json["status"] as? String,
                episodes = (json["episodes"] as? Number)?.toInt(),
                duration = (json["duration"] as? Number)?.toInt(),
                averageScore = (json["averageScore"] as? Number)?.toInt(),
                popularity = (json["popularity"] as? Number)?.toInt(),
                description = json["description"] as? String,
                genres = ((json["genres"] as? List<*>) ?: emptyList<Any>()).filterIsInstance<String>(),
                nextAiringEpisode = nae?.let {
                    mapOf(
                        "episode" to (it["episode"] as? Number)?.toInt(),
                        "airingAt" to (it["airingAt"] as? Number)?.toInt(),
                        "timeUntilAiring" to (it["timeUntilAiring"] as? Number)?.toInt(),
                    )
                },
                seasonYear = (json["seasonYear"] as? Number)?.toInt(),
                season = json["season"] as? String,
                mainStudio = studio,
                isAdult = (json["isAdult"] as? Boolean) ?: false,
                streamingEpisodes = streamEps,
            )
        }
    }
}

// ── AnimeEpisode ──────────────────────────────────────────────────────────────
data class AnimeEpisode(
    val number: Int,
    val title: String,
    val aired: Boolean = true,
    val thumbnail: String? = null,
)

// ── AnimeEmbed (stream source descriptor) ────────────────────────────────────
data class AnimeEmbed(
    val label: String,
    val server: String,   // megaplay | vidwish | miruro | allanime | watchhentai | hentaini
    val category: String, // sub | dub
    val url: String,
) {
    val displayName: String get() = when (server) {
        "miruro"      -> "Miruro · ${category.uppercase()}"
        "allanime"    -> "AllAnime · ${category.uppercase()}"
        "watchhentai" -> "WatchHentai"
        "hentaini"    -> "Hentaini"
        else          -> "$label · ${category.uppercase()}"
    }
}

// ── AnimeTrack (subtitle track) ───────────────────────────────────────────────
data class AnimeTrack(
    val url: String,
    val label: String,
    val isDefault: Boolean = false,
)

// ── AnimeStreamResult ─────────────────────────────────────────────────────────
data class AnimeStreamResult(
    val url: String,
    val referer: String,
    val origin: String,
    val tracks: List<AnimeTrack> = emptyList(),
)

// ── Anikoto models ────────────────────────────────────────────────────────────
data class AnikotoSeries(
    val id: Int,
    val episodes: List<AnikotoEpisode>,
)

data class AnikotoEpisode(
    val id: Int,
    val number: Int,
    val title: String,
    val embedId: String,
) {
    companion object {
        fun fromJson(j: Map<String, Any?>): AnikotoEpisode = AnikotoEpisode(
            id = (j["id"] as? Number)?.toInt() ?: 0,
            number = (j["number"] as? Number)?.toInt() ?: 0,
            title = (j["title"] ?: "") as String,
            embedId = (j["episode_embed_id"] ?: "").toString(),
        )
    }
}
