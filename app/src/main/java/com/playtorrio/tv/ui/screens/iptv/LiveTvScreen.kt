package com.playtorrio.tv.ui.screens.iptv

import android.content.Intent
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.playtorrio.tv.PlayerActivity
import com.playtorrio.tv.data.AppPreferences
import com.playtorrio.tv.data.iptv.LiveTvService
import com.playtorrio.tv.data.iptv.LiveTvService.Category
import com.playtorrio.tv.data.iptv.LiveTvService.Channel
import kotlinx.coroutines.launch

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)

@Composable
fun LiveTvScreen(navController: NavController) {
    val context = LocalContext.current
    val cats = remember {
        LiveTvService.CATEGORIES.filter { !it.adult || AppPreferences.showAdultContent }
    }
    var category by remember { mutableStateOf(cats.first()) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var aliveOnly by remember { mutableStateOf(AppPreferences.liveTvAliveOnly) }
    var deadUrls by remember { mutableStateOf(AppPreferences.deadChannelUrls) }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(category) {
        loading = true
        channels = LiveTvService.channels(category.slug)
        loading = false
    }

    val shown = remember(channels, query, aliveOnly, deadUrls) {
        channels
            .filter { !aliveOnly || it.url !in deadUrls }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    fun checkAlive() {
        if (checking || channels.isEmpty()) return
        checking = true
        scope.launch {
            val dead = LiveTvService.findDead(channels)
            val merged = deadUrls + dead
            AppPreferences.deadChannelUrls = merged
            deadUrls = merged
            aliveOnly = true
            AppPreferences.liveTvAliveOnly = true
            checking = false
        }
    }

    fun play(ch: Channel) {
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra("streamUrl", ch.url)
            putExtra("isIptv", true)
            putExtra("isMovie", true)
            putExtra("title", ch.name)
            putExtra("tmdbId", -1)
        })
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LiveTv, null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Live TV", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            // Inline channel search (filters the current category instantly).
            var boxFocused by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(Surface)
                    .border(if (boxFocused) 2.dp else 1.dp, if (boxFocused) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { boxFocused = it.isFocused }
                    )
                    if (query.isEmpty()) Text("Search channels…", color = Color.White.copy(0.3f), fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cats) { cat ->
                var focused by remember { mutableStateOf(false) }
                val selected = cat == category
                Text(
                    text = cat.label,
                    color = if (selected || focused) Color.White else Color.White.copy(0.7f),
                    fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Accent.copy(0.35f) else Surface)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { category = cat }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // Alive-check controls
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            var t1 by remember { mutableStateOf(false) }
            Text(
                text = if (aliveOnly) "✓ Alive only" else "Alive only",
                color = if (aliveOnly) Color(0xFF6EE7B7) else Color.White.copy(0.75f),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (aliveOnly) Color(0xFF6EE7B7).copy(0.15f) else Surface)
                    .border(if (t1) 2.dp else 1.dp, if (t1) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
                    .onFocusChanged { t1 = it.isFocused }
                    .clickable { aliveOnly = !aliveOnly; AppPreferences.liveTvAliveOnly = aliveOnly }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
            var t2 by remember { mutableStateOf(false) }
            Text(
                text = if (checking) "Checking…" else "Check alive",
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (t2) Accent.copy(0.35f) else Accent.copy(0.15f))
                    .border(if (t2) 2.dp else 1.dp, if (t2) Accent else Accent.copy(0.5f), RoundedCornerShape(999.dp))
                    .onFocusChanged { t2 = it.isFocused }
                    .clickable { checkAlive() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
            if (deadUrls.isNotEmpty()) {
                Text("${deadUrls.size} hidden", color = Color.White.copy(0.4f), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (channels.isEmpty()) "No channels in ${category.label}. Try another category."
                    else "No channels match “$query”.",
                    color = Color.White.copy(0.5f)
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shown) { ch -> ChannelCard(ch) { play(ch) } }
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: Channel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(0.14f) else Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.08f), RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }.clickable { onClick() }
            .padding(12.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
            if (!ch.logo.isNullOrBlank()) {
                AsyncImage(model = ch.logo, contentDescription = ch.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.LiveTv, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(ch.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 15.sp)
    }
}
