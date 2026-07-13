package com.playtorrio.tv.ui.screens.anime

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.playtorrio.tv.data.anime.AnimeCard
import com.playtorrio.tv.data.anime.AnimeEmbed
import com.playtorrio.tv.data.anime.AnimeEpisode

private val Purple    = Color(0xFF818CF8)
private val Gold      = Color(0xFFFFD700)
private val BgDark    = Color(0xFF0A0A0F)
private val CardBg    = Color(0xFF13131A)
private val SurfaceBg = Color(0xFF0F0F18)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    anilistId: Int,
    navController: NavController,
    vm: AnimeViewModel = viewModel(),
    autoPlayEpisode: Int? = null,
    autoPlayCategory: String? = null,
    autoPlayPositionMs: Long? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.init(context) }

    val autoLoading by vm.autoExtractLoading.collectAsState()
    val autoResult  by vm.autoExtractResult.collectAsState()
    val autoExtractWinningEmbed by vm.autoExtractWinningEmbed.collectAsState()
    val autoError   by vm.autoExtractError.collectAsState()

    val anime        by vm.selectedAnime.collectAsState()
    val episodes     by vm.episodes.collectAsState()
    val relations    by vm.relations.collectAsState()
    val seasons      by vm.seasons.collectAsState()
    val loading      by vm.detailLoading.collectAsState()
    val epPage       by vm.episodesPage.collectAsState()
    val liked        by vm.likedIds.collectAsState()
    val category     by vm.selectedCategory.collectAsState()

    LaunchedEffect(anilistId) {
        val current = vm.selectedAnime.value
        if (current == null || current.id != anilistId) {
            val stub = AnimeCard(
                id = anilistId, titleEnglish = "", titleRomaji = "", titleNative = "",
                coverLarge = null, coverExtraLarge = null, coverColor = null, bannerImage = null,
                format = null, status = null, episodes = null, duration = null,
                averageScore = null, popularity = null, description = null,
                seasonYear = null, season = null, mainStudio = null,
            )
            vm.loadDetail(stub)
        }
    }

    val pageEpisodes = vm.episodesForPage()
    val totalPages   = vm.totalEpisodePages

    // Focus requester for Watch button — auto-focused on load
    val watchFr = remember { FocusRequester() }
    var pendingEpisode by remember { mutableIntStateOf(-1) }
    
    // Auto-play trigger
    LaunchedEffect(anime, loading) {
        val a = anime
        if (!loading && a != null && autoPlayEpisode != null && autoPlayCategory != null && autoPlayEpisode > 0) {
            if (pendingEpisode != autoPlayEpisode) {
                pendingEpisode = autoPlayEpisode
                vm.selectedCategory.value = autoPlayCategory
                vm.autoExtractFirst(autoPlayEpisode, autoPlayCategory)
            }
        }
    }

    // Navigate to player once extraction succeeds
    LaunchedEffect(autoResult) {
        val r = autoResult ?: return@LaunchedEffect
        if (pendingEpisode < 0) return@LaunchedEffect

        val tracksJson = org.json.JSONArray().apply {
            r.tracks.forEach { track ->
                val obj = org.json.JSONObject()
                obj.put("url", track.url)
                obj.put("label", track.label)
                obj.put("isDefault", track.isDefault)
                put(obj)
            }
        }.toString()

        val embedsJson = org.json.JSONArray().apply {
            vm.autoExtractEmbeds.value.forEach { embed ->
                val obj = org.json.JSONObject()
                obj.put("url", embed.url)
                obj.put("server", embed.server)
                obj.put("category", embed.category)
                obj.put("label", embed.label)
                put(obj)
            }
        }.toString()

        val intent = android.content.Intent(context, com.playtorrio.tv.PlayerActivity::class.java).apply {
            putExtra("streamUrl", r.url)
            putExtra("streamReferer", r.referer)
            putExtra("animeOrigin", r.origin)
            putExtra("animeTracksJson", tracksJson)
            putExtra("animeEmbedsJson", embedsJson)
            putExtra("animeServer", autoExtractWinningEmbed?.server)
            putExtra("animeEmbedUrl", autoExtractWinningEmbed?.url)
            putExtra("animeId", anime?.id?.toString() ?: "")
            putExtra("animeCategory", vm.selectedCategory.value)
            if (pendingEpisode == autoPlayEpisode && autoPlayPositionMs != null) {
                putExtra("resumePositionMs", autoPlayPositionMs)
            }
            putExtra("title", anime?.displayTitle ?: "")
            putExtra("episodeTitle", "Episode $pendingEpisode")
            putExtra("episodeNumber", pendingEpisode)
            putExtra("seasonNumber", 1)
            putExtra("isMovie", false)
            putExtra("logoUrl", anime?.bannerOrCover)
            putExtra("backdropUrl", anime?.bannerOrCover)
            putExtra("posterUrl", anime?.coverLarge)
        }
        context.startActivity(intent)

        pendingEpisode = -1
        vm.clearExtractResult() // Prevent re-launching on return
    }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        if (loading && anime == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val inf = rememberInfiniteTransition(label = "l")
                val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                Box(Modifier.size(56.dp).alpha(a).background(Purple, CircleShape), contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
            return@Box
        }

        val a = anime ?: return@Box

        // Auto-focus Watch button when anime loads
        LaunchedEffect(a.id) {
            kotlinx.coroutines.delay(200)
            runCatching { watchFr.requestFocus() }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(bottom = 60.dp),
        ) {

            // ── Hero ──────────────────────────────────────────────────────────
            item {
                Box(Modifier.fillMaxWidth().height(380.dp)) {
                    AsyncImage(
                        model = a.bannerOrCover, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.45f),
                    )
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, BgDark), startY = 80f)
                    ))
                    Box(Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(BgDark.copy(0.9f), Color.Transparent), endX = 750f)
                    ))

                    Column(
                        Modifier.align(Alignment.BottomStart)
                            .padding(start = 28.dp, bottom = 28.dp)
                            .fillMaxWidth(0.6f)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            a.format?.let { Chip(it.replace("_", " ")) }
                            a.status?.let { Chip(it.replace("_", " "), Purple.copy(0.2f)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(a.displayTitle, color = Color.White, fontSize = 28.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            a.averageScore?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Star, null, modifier = Modifier.size(14.dp), tint = Gold)
                                    Spacer(Modifier.width(3.dp))
                                    Text("${it / 10.0}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            a.seasonYear?.let { Text("$it", color = Color.White.copy(0.55f), fontSize = 13.sp) }
                            a.episodes?.let { Text("$it episodes", color = Color.White.copy(0.55f), fontSize = 13.sp) }
                            a.mainStudio?.let { Text(it, color = Purple.copy(0.85f), fontSize = 12.sp) }
                        }
                        if (a.genres.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                a.genres.take(4).forEach { GenreChip(it) }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(
                                label = "Watch Ep 1", icon = Icons.Filled.PlayArrow,
                                bg = Purple, fg = Color.White,
                                modifier = Modifier.focusRequester(watchFr),
                            ) {
                                pendingEpisode = 1
                                vm.autoExtractFirst(1, category)
                            }
                            val isLiked = a.id in liked
                            ActionButton(
                                label = if (isLiked) "Liked" else "Like",
                                icon = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                bg = if (isLiked) Color(0xFFE03A5C) else Color.White.copy(0.12f),
                                fg = Color.White,
                            ) { vm.toggleLike(a) }
                        }
                    }

                    AsyncImage(
                        model = a.coverUrl, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 32.dp, bottom = 24.dp)
                            .width(110.dp).height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, Purple.copy(0.4f), RoundedCornerShape(12.dp)),
                    )
                }
            }

            // ── Description ───────────────────────────────────────────────────
            if (!a.cleanDescription.isBlank()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("📖 Synopsis")
                    Text(
                        a.cleanDescription,
                        color = Color.White.copy(0.72f), fontSize = 13.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 28.dp).padding(top = 8.dp),
                    )
                }
            }

            // ── Episodes header ────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬 Episodes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    CategoryTab("SUB", category == "sub") { vm.selectedCategory.value = "sub" }
                    Spacer(Modifier.width(8.dp))
                    CategoryTab("DUB", category == "dub") { vm.selectedCategory.value = "dub" }
                }
                if (totalPages > 1) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Page ${epPage + 1} / $totalPages", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        val rangeStart = epPage * vm.episodePageSize + 1
                        val rangeEnd   = minOf((epPage + 1) * vm.episodePageSize, episodes.size)
                        Text("(Ep $rangeStart – $rangeEnd)", color = Color.White.copy(0.45f), fontSize = 12.sp)
                    }
                }
            }

            // ── Episode cards — 50/page, 2-column, focusable ──────────────────
            items(pageEpisodes.chunked(2)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().focusGroup()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { ep ->
                        EpisodeCard(
                            ep = ep,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                pendingEpisode = ep.number
                                vm.autoExtractFirst(ep.number, category)
                            }
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // ── Pagination controls ────────────────────────────────────────────
            if (totalPages > 1) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().focusGroup().padding(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavButton("◀ Prev", epPage > 0) { vm.prevEpisodePage() }
                        Spacer(Modifier.width(8.dp))
                        (0 until totalPages).forEach { pg ->
                            PageButton(number = pg + 1, active = pg == epPage) { vm.episodesPage.value = pg }
                        }
                        Spacer(Modifier.width(8.dp))
                        NavButton("Next ▶", epPage < totalPages - 1) { vm.nextEpisodePage() }
                    }
                }
            }

            // ── Seasons ───────────────────────────────────────────────────────
            if (seasons.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(32.dp))
                    SectionTitle("📚 Seasons")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(seasons) { index, sea ->
                            AnimeCard(
                                anime = sea,
                                label = "S${index + 1}",
                                highlighted = sea.id == anime?.id,
                                onClick = {
                                    if (sea.id != anime?.id) {
                                        vm.loadDetail(sea)
                                        navController.navigate("anime_detail/${sea.id}") { launchSingleTop = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ── Relations ─────────────────────────────────────────────────────
            if (relations.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(32.dp))
                    SectionTitle("🔗 Related")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(relations) { rel ->
                            AnimeCard(anime = rel, onClick = {
                                vm.loadDetail(rel)
                                navController.navigate("anime_detail/${rel.id}") { launchSingleTop = true }
                            })
                        }
                    }
                }
            }
        }

        // ── Back button ───────────────────────────────────────────────────────
        Box(Modifier.padding(16.dp)) {
            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier.size(40.dp).focusProperties { down = watchFr },
                colors = ButtonDefaults.colors(
                    containerColor = Color.Black.copy(0.6f),
                    focusedContainerColor = Purple.copy(0.5f),
                    contentColor = Color.White, focusedContentColor = Color.White,
                ),
                shape = ButtonDefaults.shape(shape = CircleShape),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
            }
        }

        // ── Extracting overlay ─────────────────────────────────────────────────
        if (autoLoading && pendingEpisode >= 0) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(0.75f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val inf = rememberInfiniteTransition(label = "ext")
                    val alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                    Box(Modifier.size(64.dp).alpha(alpha).background(Purple, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("▶", color = Color.White, fontSize = 28.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Finding best source...", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Episode $pendingEpisode", color = Purple, fontSize = 13.sp)
                }
            }
        }

        // ── Extraction error toast ─────────────────────────────────────────────
        if (!autoLoading && autoError != null && pendingEpisode >= 0) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).clickable { pendingEpisode = -1 },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF1A0A0A))
                        .border(1.dp, Color(0xFFEF4444).copy(0.5f), RoundedCornerShape(16.dp))
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠️ All sources failed", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(autoError ?: "", color = Color.White.copy(0.6f), fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { vm.autoExtractFirst(pendingEpisode, category) },
                                colors = ButtonDefaults.colors(containerColor = Purple, focusedContainerColor = Purple.copy(0.7f)),
                                shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
                            ) { Text("Retry", color = Color.White, fontSize = 13.sp) }
                            Button(
                                onClick = { pendingEpisode = -1 },
                                colors = ButtonDefaults.colors(containerColor = Color.White.copy(0.1f), focusedContainerColor = Color.White.copy(0.18f)),
                                shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
                            ) { Text("Cancel", color = Color.White, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ── Source picker overlay ─────────────────────────────────────────────────────
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamPickerOverlay(
    episode: Int,
    embeds: List<AnimeEmbed>,
    loading: Boolean,
    error: String?,
    streamUrl: String?,
    onEmbed: (AnimeEmbed) -> Unit,
    onPlay: (url: String, referer: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(0.85f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.75f).fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceBg)
                .border(1.dp, Purple.copy(0.3f), RoundedCornerShape(20.dp))
                .clickable {}
                .padding(24.dp),
        ) {
            Column {
                Text("Episode $episode — Sources", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                if (loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val inf = rememberInfiniteTransition(label = "sp")
                        val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                        Text("Extracting stream...", color = Purple.copy(a), fontSize = 14.sp)
                    }
                }

                streamUrl?.let { url ->
                    Button(
                        onClick = { onPlay(url, "https://www.enma.lol/") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = Purple.copy(0.15f), focusedContainerColor = Purple.copy(0.35f),
                            contentColor = Purple, focusedContentColor = Color.White,
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
                    ) {
                        Icon(Icons.Filled.PlayCircle, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("▶  Play now", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                }

                error?.let {
                    Text(it, color = Color(0xFFEF4444), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }

                LazyColumn(
                    modifier = Modifier.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val subs = embeds.filter { it.category == "sub" }
                    val dubs = embeds.filter { it.category == "dub" }
                    if (subs.isNotEmpty()) {
                        item { Text("SUB", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)) }
                        items(subs) { embed -> EmbedRow(embed, onEmbed) }
                    }
                    if (dubs.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("DUB", color = Color(0xFFFFB347), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        items(dubs) { embed -> EmbedRow(embed, onEmbed) }
                    }
                }
            }
        }
    }
}

// EmbedRow — TV-focusable via clickable (handles Enter on D-pad)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmbedRow(embed: AnimeEmbed, onEmbed: (AnimeEmbed) -> Unit) {
    val (serverColor, serverIcon) = when (embed.server) {
        "megaplay"    -> Purple to "🎬"
        "vidwish"     -> Color(0xFF38BDF8) to "📺"
        "miruro"      -> Color(0xFF34D399) to "🌿"
        "allanime"    -> Color(0xFFFF6B6B) to "🔴"
        "watchhentai" -> Color(0xFFFF69B4) to "🔞"
        "hentaini"    -> Color(0xFFFF69B4) to "🔞"
        else          -> Color.White to "▶"
    }
    Button(
        onClick = { onEmbed(embed) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(0.05f), focusedContainerColor = Purple.copy(0.2f),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(serverIcon, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(embed.displayName, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp), tint = serverColor)
    }
}

// EpisodeCard — TV Card for proper D-pad focus + scale
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(ep: AnimeEpisode, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = { if (ep.aired) onClick() },
        modifier = modifier.alpha(if (ep.aired) 1f else 0.35f),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = Purple.copy(0.2f)),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(0.06f))),
            focusedBorder = Border(BorderStroke(1.dp, Purple)),
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 72.dp, height = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (ep.thumbnail != null) {
                    AsyncImage(ep.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.Movie, null, modifier = Modifier.size(22.dp), tint = Purple.copy(0.5f))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Ep ${ep.number}", color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(ep.title, color = Color.White, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
            if (ep.aired) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp), tint = Purple.copy(0.7f))
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
@Composable private fun Chip(text: String, bg: Color = Color.White.copy(0.1f)) {
    Box(Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = Color.White.copy(0.75f), fontSize = 10.sp)
    }
}

@Composable private fun GenreChip(genre: String) {
    Box(Modifier.background(Purple.copy(0.15f), RoundedCornerShape(4.dp))
        .border(1.dp, Purple.copy(0.3f), RoundedCornerShape(4.dp))
        .padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(genre, color = Purple.copy(0.9f), fontSize = 10.sp)
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 28.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        colors = ButtonDefaults.colors(
            containerColor = bg, focusedContainerColor = bg.copy(0.8f),
            contentColor = fg, focusedContentColor = fg,
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryTab(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (active) Purple else Color.White.copy(0.08f),
            focusedContainerColor = if (active) Purple.copy(0.8f) else Color.White.copy(0.14f),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(6.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(34.dp),
        colors = ButtonDefaults.colors(
            containerColor = Purple.copy(if (enabled) 0.8f else 0.2f),
            focusedContainerColor = Purple,
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
    ) { Text(label, fontSize = 12.sp) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PageButton(number: Int, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(2.dp).size(32.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (active) Purple else Color.White.copy(0.08f),
            focusedContainerColor = Purple.copy(0.7f),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        shape = ButtonDefaults.shape(CircleShape),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text("$number", fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}
