package com.playtorrio.tv.ui.screens.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    var genre by remember { mutableStateOf(initialGenre?.takeIf { it in genres } ?: "All") }
    var sort by remember { mutableStateOf(SORTS.first()) }
    var results by remember { mutableStateOf<List<AnimeCardModel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(genre, sort) {
        loading = true
        results = runCatching {
            AnimeService.browse(
                genre = genre.takeIf { it != "All" },
                sort = sort.second,
                perPage = 40,
            )
        }.getOrDefault(emptyList())
        loading = false
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
            items(SORTS) { s -> Chip(s.first, s == sort) { sort = s } }
        }
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing found for these filters.", color = Color.White.copy(0.5f))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(results) { anime ->
                    AnimeCard(anime = anime, onClick = { navController.navigate("anime_detail/${anime.id}") })
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
