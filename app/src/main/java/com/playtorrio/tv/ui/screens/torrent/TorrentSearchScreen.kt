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
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)

// Quick filters appended to the query — practical "categories" for torrents.
private val FILTERS = listOf("All", "1080p", "2160p", "x265", "Anime")

@Composable
fun TorrentSearchScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var results by remember { mutableStateOf<List<TorrentResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    val searchFocus = remember { FocusRequester() }

    fun runSearch() {
        val q = (query.trim() + if (filter == "All") "" else " $filter").trim()
        if (q.isBlank()) return
        loading = true
        searched = true
        scope.launch {
            results = runCatching { TorrentSearchService.searchRaw(q) }.getOrDefault(emptyList())
            loading = false
        }
    }

    fun play(result: TorrentResult) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("magnetUri", result.magnetLink)
            putExtra("title", result.name)
            putExtra("isMovie", true)
            putExtra("tmdbId", -1)
        }
        context.startActivity(intent)
    }

    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Text("Torrent Search", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Search torrents across indexers and play instantly via the built-in engine",
            color = Color.White.copy(0.5f), fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))

        // Search box
        var boxFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Surface)
                .border(
                    width = if (boxFocused) 2.dp else 1.dp,
                    color = if (boxFocused) Accent else Color.White.copy(0.12f),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus)
                        .onFocusChanged { boxFocused = it.isFocused }
                )
                if (query.isEmpty()) {
                    Text("Search movies, shows, anime…", color = Color.White.copy(0.3f), fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Quick-filter chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(FILTERS) { f ->
                var chipFocused by remember { mutableStateOf(false) }
                val selected = f == filter
                Text(
                    text = f,
                    color = if (selected || chipFocused) Color.White else Color.White.copy(0.7f),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Accent.copy(0.35f) else Surface)
                        .border(
                            width = if (chipFocused) 2.dp else 1.dp,
                            color = if (chipFocused) Accent else Color.White.copy(0.12f),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .onFocusChanged { chipFocused = it.isFocused }
                        .focusable()
                        .clickable { filter = f; if (searched) runSearch() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            searched && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No torrents found. Try a different search.", color = Color.White.copy(0.5f))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(0.14f) else Surface)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                result.name, color = Color.White, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.People, null, tint = Color(0xFF6EE7B7), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text("${result.seeders}", color = Color(0xFF6EE7B7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(result.size, color = Color.White.copy(0.6f), fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text(result.source, color = Accent.copy(0.9f), fontSize = 11.sp)
            }
        }
        Icon(Icons.Filled.Download, null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}
