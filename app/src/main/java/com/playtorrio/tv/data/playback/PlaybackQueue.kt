package com.playtorrio.tv.data.playback

/**
 * In-memory hand-off of the "sibling" items from the catalog/filter the user was
 * browsing, so the player can show them as an Up Next + a "more from this list"
 * slideshow — even for addon/torrent/anime items that have no TMDB/IMDB id.
 *
 * A browse screen sets [items] (+ a [label] identifying the source list) right
 * before navigating into an item; the player reads it on launch. Kept in memory
 * (not Intent extras) because the lists can be large and carry thumbnails.
 */
object PlaybackQueue {

    /** One entry from a browsed list. [kind] selects how the player resolves it. */
    data class Item(
        val kind: Kind,
        val title: String,
        val thumbnailUrl: String?,
        // kind = ADDON
        val addonId: String? = null,
        val stremioType: String? = null,
        val stremioId: String? = null,
        // kind = TORRENT
        val magnet: String? = null,
        // kind = ANIME
        val animeId: String? = null,
        // kind = TMDB
        val tmdbId: Int = 0,
        val isMovie: Boolean = true,
    ) {
        /** Stable identity used to exclude the currently-playing item + de-dupe. */
        val identity: String
            get() = when (kind) {
                Kind.ADDON -> "addon:$stremioId"
                Kind.TORRENT -> "torrent:$magnet"
                Kind.ANIME -> "anime:$animeId"
                Kind.TMDB -> "tmdb:$tmdbId"
            }
    }

    enum class Kind { TMDB, ADDON, TORRENT, ANIME }

    @Volatile
    var items: List<Item> = emptyList()
        private set

    @Volatile
    var label: String = ""
        private set

    /** Called by a browse screen before opening an item. */
    fun set(label: String, items: List<Item>) {
        this.label = label
        this.items = items
    }

    fun clear() {
        label = ""
        items = emptyList()
    }
}
