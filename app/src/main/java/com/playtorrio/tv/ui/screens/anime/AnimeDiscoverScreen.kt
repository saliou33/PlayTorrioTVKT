package com.playtorrio.tv.ui.screens.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.playtorrio.tv.data.AppPreferences
import com.playtorrio.tv.data.anime.AnimeService
import com.playtorrio.tv.data.anime.AnimeCard as AnimeCardModel

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)

private val GENRES = listOf(
    "All", "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror",
    "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance",
    "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
)
private val SORTS = listOf(
    "Popular" to "POPULARITY_DESC",
    "Trending" to "TRENDING_DESC",
    "Top Rated" to "SCORE_DESC",
    "Newest" to "START_DATE_DESC",
)

@Composable
fun AnimeDiscoverScreen(navController: NavController, initialGenre: String? = null) {
    val scope = rememberCoroutineScope()
    val genres = remember {
        if (AppPreferences.showAdultContent) GENRES + "Hentai" else GENRES
    }
    // Seed from the back-nav cache so returning from a detail page restores the
    // filter, results, pagination and scroll instantly instead of refetching.
    val cache = com.playtorrio.tv.ui.ScreenStateCache.AnimeDiscover
    val restoring = initialGenre == null && cache.results.isNotEmpty()
    // Filters live in rememberSaveable so they also survive activity/process
    // recreation (low-RAM TVs kill the main activity behind the player).
    var genre by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(
            initialGenre?.takeIf { it in genres } ?: (if (restoring) cache.genre ?: "All" else "All")
        )
    }
    var sortLabel by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(if (restoring) cache.sortLabel ?: SORTS.first().first else SORTS.first().first)
    }
    val sort = SORTS.firstOrNull { it.first == sortLabel } ?: SORTS.first()
    var results by remember {
        mutableStateOf(if (restoring) cache.results else emptyList<AnimeCardModel>())
    }
    var loading by remember { mutableStateOf(!restoring) }
    // Pagination: keep loading more pages as the grid scrolls to its end.
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = if (restoring) cache.firstVisibleIndex else 0
    )
    var page by remember { mutableIntStateOf(if (restoring) cache.page else 1) }
    var hasMore by remember { mutableStateOf(if (restoring) cache.hasMore else true) }
    var loadingMore by remember { mutableStateOf(false) }
    val perPage = 40

    // Write-through to the cache so the next back-return restores this state.
    LaunchedEffect(results, page, hasMore, genre, sort) {
        cache.genre = genre
        cache.sortLabel = sort.first
        cache.results = results
        cache.page = page
        cache.hasMore = hasMore
    }
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        cache.firstVisibleIndex = gridState.firstVisibleItemIndex
    }

    // Reset + first page whenever the filter changes (skipped when we restored
    // matching cached results). Tracked locally so the cache write-through above
    // can't mask a genuine filter change.
    var loadedKey by remember { mutableStateOf(if (restoring) "${genre}|${sort.first}" else "") }
    LaunchedEffect(genre, sort) {
        val key = "${genre}|${sort.first}"
        if (key == loadedKey && results.isNotEmpty()) return@LaunchedEffect
        loading = true
        page = 1
        hasMore = true
        val first = runCatching {
            AnimeService.browse(
                genre = genre.takeIf { it != "All" },
                sort = sort.second,
                page = 1,
                perPage = perPage,
            )
        }.getOrDefault(emptyList())
        results = first
        hasMore = first.size >= perPage
        loading = false
        loadedKey = key
    }

    // Load-more when scrolled near the end.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= results.size - 8
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, loadingMore, loading) {
        if (shouldLoadMore && hasMore && !loadingMore && !loading && results.isNotEmpty()) {
            loadingMore = true
            val next = page + 1
            val more = runCatching {
                AnimeService.browse(
                    genre = genre.takeIf { it != "All" },
                    sort = sort.second,
                    page = next,
                    perPage = perPage,
                )
            }.getOrDefault(emptyList())
            if (more.isEmpty()) {
                hasMore = false
            } else {
                // De-dupe by id in case pages overlap.
                val existing = results.mapTo(HashSet()) { it.id }
                results = results + more.filter { it.id !in existing }
                page = next
                hasMore = more.size >= perPage
            }
            loadingMore = false
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("Discover Anime", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        // Genre chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(genres) { g -> Chip(g, g == genre) { genre = g } }
        }
        Spacer(Modifier.height(10.dp))
        // Sort chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(SORTS) { s -> Chip(s.first, s == sort) { sortLabel = s.first } }
        }
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing found for these filters.", color = Color.White.copy(0.5f))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(results) { anime ->
                    AnimeCard(anime = anime, onClick = {
                        // Capture the filtered list so the player's Up Next + slideshow
                        // can offer more from this same filter.
                        com.playtorrio.tv.data.playback.PlaybackQueue.set(
                            label = "anime:${genre}:${sort.second}",
                            items = results.map { a ->
                                com.playtorrio.tv.data.playback.PlaybackQueue.Item(
                                    kind = com.playtorrio.tv.data.playback.PlaybackQueue.Kind.ANIME,
                                    title = a.displayTitle,
                                    thumbnailUrl = a.coverUrl,
                                    animeId = a.id.toString(),
                                    isMovie = false,
                                )
                            }
                        )
                        navController.navigate("anime_detail/${anime.id}")
                    })
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (selected || focused) Color.White else Color.White.copy(0.7f),
        fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent.copy(0.35f) else Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}
