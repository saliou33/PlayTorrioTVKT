package com.playtorrio.tv.ui

import com.playtorrio.tv.data.anime.AnimeCard
import com.playtorrio.tv.data.torrent.TorrentResult

/**
 * In-memory UI-state holders for browse screens that keep their state in plain
 * `remember` (no ViewModel). Composables leave composition when you navigate
 * into a detail page, so without these, pressing Back rebuilt each screen from
 * scratch — refetching from the network and resetting filters/scroll. Screens
 * seed their `remember` state from here and write back on change, making
 * back-navigation instant. Process-lifetime by design; tiny footprint.
 */
object ScreenStateCache {

    object AnimeDiscover {
        var genre: String? = null
        var sortLabel: String? = null
        var results: List<AnimeCard> = emptyList()
        var page: Int = 1
        var hasMore: Boolean = true
        var firstVisibleIndex: Int = 0

        fun matches(g: String, s: String) = genre == g && sortLabel == s && results.isNotEmpty()
        fun clear() { genre = null; sortLabel = null; results = emptyList(); page = 1; hasMore = true; firstVisibleIndex = 0 }
    }

    object TorrentSearch {
        var query: String = ""
        var categoryName: String? = null
        var results: List<TorrentResult> = emptyList()
        var showingPopular: Boolean = true
    }

    object LiveTv {
        var tabKey: String? = null
        var query: String = ""
    }
}
