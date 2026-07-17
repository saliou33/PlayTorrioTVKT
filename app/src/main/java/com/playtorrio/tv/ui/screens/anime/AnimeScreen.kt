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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val Purple  = Color(0xFF818CF8)
private val Gold    = Color(0xFFFFD700)
private val BgDark  = Color(0xFF0A0A0F)
private val CardBg  = Color(0xFF13131A)

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AnimeScreen(
    navController: NavController,
    vm: AnimeViewModel = viewModel(),
) {
    val context  = LocalContext.current
    LaunchedEffect(Unit) { vm.init(context) }

    val spotlight   by vm.spotlight.collectAsState()
    val top10       by vm.top10.collectAsState()
    val trending    by vm.trending.collectAsState()
    val topAiring   by vm.topAiring.collectAsState()
    val mostPopular by vm.mostPopular.collectAsState()
    val topRated    by vm.topRated.collectAsState()
    val latestDone  by vm.latestDone.collectAsState()
    val recentEps   by vm.recentEps.collectAsState()
    val hentai      by vm.hentai.collectAsState()
    val isLoading   by vm.isLoading.collectAsState()
    
    val continueWatching = remember { mutableStateOf(emptyList<com.playtorrio.tv.data.watch.WatchProgress>()) }
    var editMode by remember { mutableStateOf(false) }
    val listFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        continueWatching.value = if (com.playtorrio.tv.data.AppPreferences.playHistoryEnabled)
            com.playtorrio.tv.data.watch.WatchProgressStore.load()
                .filter { it.kind == com.playtorrio.tv.data.watch.WatchKind.ANIME }
        else emptyList()
    }

    // Spotlight auto-scroll
    var spotIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(spotlight) {
        if (spotlight.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5000)
            spotIdx = (spotIdx + 1) % spotlight.size
        }
    }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        if (isLoading) { AnimeLoadingIndicator(); return@Box }

        // ── Main scrollable list ──────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer()
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
                            FocusRequester.Cancel
                        } else {
                            FocusRequester.Default
                        }
                    }
                },
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            // Invisible 0-height anchor so TopBar down-press can land focus here
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.dp)
                        .focusRequester(listFocusRequester)
                        .focusable()
                )
            }
            // Spotlight hero
            if (spotlight.isNotEmpty()) {
                item {
                    AnimeHero(
                        anime = spotlight[spotIdx],
                        onPlay    = { navController.navigate("anime_detail/${spotlight[spotIdx].id}") },
                        onDetails = { navController.navigate("anime_detail/${spotlight[spotIdx].id}") },
                    )
                }
                item { SpotlightDots(count = spotlight.size, current = spotIdx) }
            }

            if (continueWatching.value.isNotEmpty()) {
                item {
                    ContinueWatchingAnimeRow(
                        items = continueWatching.value,
                        editMode = editMode,
                        onItemRemoved = { entry ->
                            continueWatching.value = com.playtorrio.tv.data.watch.WatchProgressStore.remove(entry.key)
                                .filter { it.kind == com.playtorrio.tv.data.watch.WatchKind.ANIME }
                            if (continueWatching.value.isEmpty()) editMode = false
                        },
                        onItemClicked = { entry ->
                            navController.navigate("anime_detail/${entry.animeId}?autoPlayEp=${entry.episodeNumber}&autoPlayCat=${entry.animeCategory}&pos=${entry.positionMs}")
                        },
                        onToggleEdit = { editMode = !editMode }
                    )
                }
            }

            animeRow("Top 10 Today",      Icons.Filled.Whatshot,            top10,       navController)
            animeRow("Trending Now",      Icons.Filled.LocalFireDepartment, trending,    navController)
            animeRow("Top Airing",        Icons.Filled.Sensors,             topAiring,   navController)
            animeRow("Most Popular",      Icons.Filled.Star,                mostPopular, navController)
            animeRow("Top Rated",         Icons.Filled.EmojiEvents,         topRated,    navController)
            animeRow("Latest Completed",  Icons.Filled.CheckCircle,         latestDone,  navController)
            animeRow("Recent Episodes",   Icons.Filled.NewReleases,         recentEps,   navController)
            if (hentai.isNotEmpty()) {
                animeRow("18+ (Hentai)",  Icons.Filled.Whatshot,            hentai,      navController)
            }
        }

        // ── Top bar overlay ───────────────────────────────────────────────────
        AnimeTopBar(
            onSearch = { navController.navigate("anime_search") },
            onDiscover = { navController.navigate("anime_discover") },
            onMature = if (com.playtorrio.tv.data.AppPreferences.showAdultContent) {
                { navController.navigate("anime_discover?genre=Hentai") }
            } else null,
            onBack   = { navController.popBackStack() },
            onFocusDown = { runCatching { listFocusRequester.requestFocus() } },
        )
    }
}

// ── Row builder ───────────────────────────────────────────────────────────────
private fun LazyListScope.animeRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<com.playtorrio.tv.data.anime.AnimeCard>,
    navController: NavController,
) {
    if (items.isEmpty()) return
    item {
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Purple, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier,
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items) { anime ->
                AnimeCard(anime = anime, onClick = {
                    navController.navigate("anime_detail/${anime.id}")
                })
            }
        }
    }
}

// ── Hero banner ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeHero(
    anime: com.playtorrio.tv.data.anime.AnimeCard,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    val playFr = remember { FocusRequester() }
    // Auto-focus the Watch button when hero first appears
    LaunchedEffect(anime.id) {
        kotlinx.coroutines.delay(150)
        runCatching { playFr.requestFocus() }
    }

    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = anime.bannerOrCover, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.55f),
        )
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Transparent, BgDark), startY = 120f)
        ))
        Box(Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(BgDark.copy(alpha = 0.92f), Color.Transparent), endX = 700f)
        ))

        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 40.dp)
                .fillMaxWidth(0.55f),
        ) {
            anime.format?.let { fmt ->
                Box(
                    Modifier.background(Purple.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .border(1.dp, Purple.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Text(fmt.replace("_", " "), color = Purple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(8.dp))
            }

            Text(anime.displayTitle, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                anime.averageScore?.let { score ->
                    Icon(Icons.Filled.Star, null, modifier = Modifier.size(14.dp), tint = Gold)
                    Spacer(Modifier.width(3.dp))
                    Text("${score / 10.0}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(12.dp))
                }
                anime.seasonYear?.let { Text("$it", color = Color.White.copy(0.6f), fontSize = 13.sp); Spacer(Modifier.width(12.dp)) }
                anime.episodes?.let { Text("$it eps", color = Color.White.copy(0.6f), fontSize = 13.sp) }
            }

            if (anime.cleanDescription.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(anime.cleanDescription, color = Color.White.copy(0.7f), fontSize = 12.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFr).height(40.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Purple, focusedContainerColor = Color.White,
                        contentColor = Color.White, focusedContentColor = BgDark,
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Watch", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = onDetails,
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(0.12f), focusedContainerColor = Color.White.copy(0.22f),
                        contentColor = Color.White, focusedContentColor = Color.White,
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                ) {
                    Icon(Icons.Filled.Info, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Details", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SpotlightDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 20.dp else 6.dp, label = "dot")
            Box(
                Modifier.padding(horizontal = 3.dp).height(6.dp).width(w)
                    .clip(CircleShape)
                    .background(if (i == current) Purple else Color.White.copy(0.25f))
            )
        }
    }
}

// ── Anime card — TV-focusable ─────────────────────────────────────────────────
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeCard(
    anime: com.playtorrio.tv.data.anime.AnimeCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    highlighted: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(130.dp),
        colors = CardDefaults.colors(containerColor = CardBg),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = if (highlighted) CardDefaults.border(
            border = Border(androidx.compose.foundation.BorderStroke(2.dp, Purple), shape = RoundedCornerShape(10.dp))
        ) else CardDefaults.border(),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                AsyncImage(
                    model = anime.coverUrl, contentDescription = anime.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
                )
                if (label != null) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Purple.copy(0.85f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                anime.averageScore?.let { score ->
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, modifier = Modifier.size(10.dp), tint = Gold)
                            Spacer(Modifier.width(2.dp))
                            Text("${score / 10.0}", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(50.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, CardBg))))
            }
            Column(Modifier.padding(8.dp)) {
                Text(anime.displayTitle, color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    anime.seasonYear?.let { Text("$it", color = Color.White.copy(0.45f), fontSize = 10.sp); Spacer(Modifier.width(6.dp)) }
                    anime.format?.let { Text(it.replace("_", " "), color = Purple.copy(0.8f), fontSize = 10.sp) }
                }
            }
        }
    }
}

// ── Top bar — each button is a TV-focusable Button ───────────────────────────
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeTopBar(onSearch: () -> Unit, onDiscover: () -> Unit, onBack: () -> Unit, onMature: (() -> Unit)? = null, onFocusDown: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(BgDark.copy(0.95f), Color.Transparent)))
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) { onFocusDown(); true } else false
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(0.08f),
                focusedContainerColor = Purple.copy(0.3f),
                contentColor = Color.White, focusedContentColor = Color.White,
            ),
            shape = ButtonDefaults.shape(shape = CircleShape),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(Purple, CircleShape), contentAlignment = Alignment.Center) {
                Text("A", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Text("Anime", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("Hub", color = Purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))

        if (onMature != null) {
            var mFocused by remember { mutableStateOf(false) }
            Text(
                "18+",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (mFocused) Purple else Purple.copy(0.35f))
                    .onFocusChanged { mFocused = it.isFocused }
                    .clickable { onMature() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Spacer(Modifier.width(10.dp))
        }

        Button(
            onClick = onDiscover,
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(0.08f),
                focusedContainerColor = Purple.copy(0.3f),
                contentColor = Color.White, focusedContentColor = Color.White,
            ),
            shape = ButtonDefaults.shape(shape = CircleShape),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Filled.Explore, null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onSearch,
            modifier = Modifier.size(40.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(0.08f),
                focusedContainerColor = Purple.copy(0.3f),
                contentColor = Color.White, focusedContentColor = Color.White,
            ),
            shape = ButtonDefaults.shape(shape = CircleShape),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Loading ───────────────────────────────────────────────────────────────────
@Composable
private fun AnimeLoadingIndicator() {
    val inf = rememberInfiniteTransition(label = "loading")
    val alpha by inf.animateFloat(0.3f, 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).alpha(alpha).background(Purple, CircleShape),
                contentAlignment = Alignment.Center) {
                Text("A", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(16.dp))
            Text("Loading Anime...", color = Color.White.copy(0.6f), fontSize = 14.sp)
        }
    }
}

// -- Continue Watching Anime Row -----------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueWatchingAnimeRow(
    items: List<com.playtorrio.tv.data.watch.WatchProgress>,
    editMode: Boolean,
    onItemRemoved: (com.playtorrio.tv.data.watch.WatchProgress) -> Unit,
    onItemClicked: (com.playtorrio.tv.data.watch.WatchProgress) -> Unit,
    onToggleEdit: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.History, null, tint = Purple, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Continue Watching",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onToggleEdit,
                colors = ButtonDefaults.colors(
                    containerColor = if (editMode) Color(0xFFEF4444).copy(0.2f) else Color.White.copy(0.08f),
                    focusedContainerColor = if (editMode) Color(0xFFEF4444).copy(0.5f) else Purple.copy(0.3f),
                ),
                shape = ButtonDefaults.shape(CircleShape),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(32.dp)
            ) {
                Icon(if (editMode) Icons.Filled.Close else Icons.Filled.Edit, null, modifier = Modifier.size(16.dp), tint = if (editMode) Color(0xFFEF4444) else Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))

        LazyRow(
            modifier = Modifier,
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items) { entry ->
                ContinueWatchingAnimeCard(
                    entry = entry,
                    editMode = editMode,
                    onClick = { if (editMode) onItemRemoved(entry) else onItemClicked(entry) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueWatchingAnimeCard(
    entry: com.playtorrio.tv.data.watch.WatchProgress,
    editMode: Boolean,
    onClick: () -> Unit,
) {
    val ratio = if (entry.durationMs > 0L) (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) else 0f
    Card(
        onClick = onClick,
        modifier = Modifier.width(220.dp).height(74.dp).border(
            width = if (editMode) 2.dp else 0.dp,
            color = if (editMode) Color(0xFFEF4444) else Color.Transparent,
            shape = RoundedCornerShape(10.dp),
        ),
        colors = CardDefaults.colors(containerColor = CardBg),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 54.dp, height = 74.dp)) {
                AsyncImage(
                    model = entry.posterUrl, contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)),
                )
                if (editMode) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                Text(entry.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val subText = buildString {
                    if (entry.episodeNumber != null) append("Ep ")
                    if (entry.animeCategory != null) {
                        if (isNotEmpty()) append(" � ")
                        append(entry.animeCategory.uppercase())
                    }
                }
                if (subText.isNotEmpty()) {
                    Text(subText, color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatWatchTimeAnime(entry.positionMs) + if (entry.durationMs > 0) " / " + formatWatchTimeAnime(entry.durationMs) else "",
                    color = Color.White.copy(0.5f), fontSize = 9.sp, maxLines = 1
                )
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(0.1f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(ratio.coerceAtLeast(0.02f)).background(Purple))
                }
            }
        }
    }
}

private fun formatWatchTimeAnime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
