package com.playtorrio.tv.ui.screens.torrent

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.playtorrio.tv.PlayerActivity
import com.playtorrio.tv.data.torrent.TorrentResult
import com.playtorrio.tv.data.torrent.TorrentSearchService
import com.playtorrio.tv.data.torrent.TorrentSearchService.TorrentCategory
import kotlinx.coroutines.launch

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)

private fun qualityOf(name: String): String? {
    val n = name.lowercase()
    return when {
        n.contains("2160") || n.contains("4k") -> "4K"
        n.contains("1080") -> "1080p"
        n.contains("720") -> "720p"
        n.contains("480") -> "480p"
        else -> null
    }
}

@Composable
fun TorrentSearchScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Seed from the back-nav cache so returning from the player restores the
    // query/category/results instantly instead of refetching.
    val cache = com.playtorrio.tv.ui.ScreenStateCache.TorrentSearch
    val restoring = cache.results.isNotEmpty()
    // rememberSaveable: query/category also survive activity/process recreation.
    var query by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(if (restoring) cache.query else "")
    }
    var category by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(
            if (restoring) TorrentCategory.entries.firstOrNull { it.name == cache.categoryName } ?: TorrentCategory.ALL
            else TorrentCategory.ALL
        )
    }
    var results by remember { mutableStateOf(if (restoring) cache.results else emptyList<TorrentResult>()) }
    var loading by remember { mutableStateOf(false) }
    var showingPopular by remember { mutableStateOf(if (restoring) cache.showingPopular else true) }

    val searchFocus = remember { FocusRequester() }

    // Debounced search-as-you-type: re-runs whenever the query or category
    // changes; a blank query shows the Popular list for the category. The
    // LaunchedEffect restart cancels the previous 450ms wait, so we only hit
    // the network after the user pauses typing.
    var loadedKey by remember { mutableStateOf(if (restoring) "${cache.query.trim()}|${category.name}" else null) }
    LaunchedEffect(query, category) {
        val q = query.trim()
        val key = "$q|${category.name}"
        if (key == loadedKey && results.isNotEmpty()) return@LaunchedEffect
        if (q.isBlank()) {
            showingPopular = true
            loading = true
            results = runCatching { TorrentSearchService.popular(category) }.getOrDefault(emptyList())
            loading = false
        } else {
            showingPopular = false
            kotlinx.coroutines.delay(450)
            loading = true
            results = runCatching { TorrentSearchService.searchCategory(q, category) }.getOrDefault(emptyList())
            loading = false
        }
        loadedKey = key
        cache.query = query
        cache.categoryName = category.name
        cache.results = results
        cache.showingPopular = showingPopular
    }

    fun play(result: TorrentResult) {
        // Capture the current result list so the player can offer an Up Next +
        // "more from this search" slideshow (posters resolved lazily by name).
        com.playtorrio.tv.data.playback.PlaybackQueue.set(
            label = "torrent:$category",
            items = results.map { r ->
                com.playtorrio.tv.data.playback.PlaybackQueue.Item(
                    kind = com.playtorrio.tv.data.playback.PlaybackQueue.Kind.TORRENT,
                    title = r.name,
                    thumbnailUrl = null,
                    magnet = r.magnetLink,
                    isMovie = true,
                )
            }
        )
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra("magnetUri", result.magnetLink)
            putExtra("title", result.name)
            putExtra("isMovie", true)
            putExtra("tmdbId", -1)
        })
    }

    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("Torrent Search", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Search box
        var boxFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface)
                .border(if (boxFocused) 2.dp else 1.dp, if (boxFocused) Accent else Color.White.copy(0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* auto via debounce */ }),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { boxFocused = it.isFocused }
                )
                if (query.isEmpty()) Text("Search movies, shows, anime, music, games…", color = Color.White.copy(0.3f), fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Category chips (mature category hidden unless enabled in Settings)
        val cats = remember {
            TorrentCategory.entries.filter {
                !it.adult || com.playtorrio.tv.data.AppPreferences.showAdultContent
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cats) { cat ->
                var chipFocused by remember { mutableStateOf(false) }
                val selected = cat == category
                Text(
                    text = cat.label,
                    color = if (selected || chipFocused) Color.White else Color.White.copy(0.7f),
                    fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Accent.copy(0.35f) else Surface)
                        .border(if (chipFocused) 2.dp else 1.dp, if (chipFocused) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
                        .onFocusChanged { chipFocused = it.isFocused }
                        .clickable { category = cat }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showingPopular) Icon(Icons.Filled.Whatshot, null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (showingPopular) "Popular · ${category.label}" else "Results · ${category.label}",
                color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing found. Try another search or category.", color = Color.White.copy(0.5f))
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(results) { _, r -> TorrentRow(r) { play(r) } }
            }
        }
    }
}

@Composable
private fun TorrentRow(result: TorrentResult, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val quality = qualityOf(result.name)
    // Lazily resolve a TMDB poster for this result (best-effort, cached).
    val poster by produceState<String?>(null, result.name) {
        value = runCatching { TorrentSearchService.posterFor(result.name) }.getOrNull()
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(0.14f) else Surface)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }.clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 44.dp, height = 62.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.08f)),
            contentAlignment = Alignment.Center
        ) {
            if (poster != null) {
                AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Movie, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(result.name, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.People, null, tint = Color(0xFF6EE7B7), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text("${result.seeders}", color = Color(0xFF6EE7B7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(result.size, color = Color.White.copy(0.6f), fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text(result.source, color = Accent.copy(0.9f), fontSize = 11.sp)
                if (quality != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        quality, color = Color(0xFFFDBA74), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFDBA74).copy(0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Icon(Icons.Filled.Download, null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}
