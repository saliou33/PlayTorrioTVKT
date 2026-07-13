package com.playtorrio.tv.ui.screens.iptv

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.playtorrio.tv.PlayerActivity
import com.playtorrio.tv.data.iptv.LiveTvService
import com.playtorrio.tv.data.iptv.LiveTvService.Channel

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)

@Composable
fun LiveTvScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var category by remember { mutableStateOf(LiveTvService.CATEGORIES.first()) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(category) {
        loading = true
        channels = LiveTvService.channels(category.second)
        loading = false
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
            Spacer(Modifier.width(12.dp))
            Text("Free channels · iptv-org", color = Color.White.copy(0.45f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(LiveTvService.CATEGORIES) { cat ->
                var focused by remember { mutableStateOf(false) }
                val selected = cat == category
                Text(
                    text = cat.first,
                    color = if (selected || focused) Color.White else Color.White.copy(0.7f),
                    fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Accent.copy(0.35f) else Surface)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
                        .onFocusChanged { focused = it.isFocused }.focusable()
                        .clickable { category = cat }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No channels in ${category.first}. Try another category.", color = Color.White.copy(0.5f))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(channels) { ch -> ChannelCard(ch) { play(ch) } }
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
            .onFocusChanged { focused = it.isFocused }.focusable().clickable { onClick() }
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
