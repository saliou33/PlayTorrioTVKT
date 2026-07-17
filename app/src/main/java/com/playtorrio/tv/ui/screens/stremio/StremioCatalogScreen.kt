package com.playtorrio.tv.ui.screens.stremio

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.playtorrio.tv.data.stremio.CatalogDeclaration
import com.playtorrio.tv.data.stremio.ExtraProperty
import com.playtorrio.tv.data.stremio.StremioMetaPreview

private val AccentSecondary = Color(0xFFC084FC)
private val SurfaceGlass = Color.White.copy(alpha = 0.06f)
private val SurfaceGlassBorder = Color.White.copy(alpha = 0.12f)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StremioCatalogScreen(
    addonId: String,
    type: String,
    catalogId: String,
    title: String,
    navController: NavController,
    viewModel: StremioCatalogViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(addonId, type, catalogId) {
        viewModel.load(addonId, type, catalogId)
    }

    // Scroll-triggered pagination: load the next page as focus/scroll nears the
    // end of the grid (not only when an item is clicked).
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= state.items.size - 8
        }
    }
    LaunchedEffect(shouldLoadMore, state.hasMore, state.isLoadingMore) {
        if (shouldLoadMore && state.hasMore && !state.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    val selectedCatalog = state.catalogs.getOrNull(state.selectedCatalogIndex)
    var catQuery by remember { mutableStateOf("") }
    val shownItems = remember(state.items, catQuery) {
        if (catQuery.isBlank()) state.items
        else state.items.filter { it.name.contains(catQuery.trim(), ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    navController.popBackStack()
                    true
                } else false
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left catalog navbar
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(290.dp)
                    .background(SurfaceGlass)
                    .border(1.dp, SurfaceGlassBorder)
                    .padding(horizontal = 14.dp, vertical = 18.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = (state.addonName.ifBlank { title }).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "CATALOGS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        ),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.catalogs.indices.toList()) { index ->
                            val catalog = state.catalogs[index]
                            CatalogMenuItem(
                                catalog = catalog,
                                isSelected = index == state.selectedCatalogIndex,
                                onClick = { viewModel.selectCatalog(index) }
                            )
                        }
                    }
                }
            }

            // Right content area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = (selectedCatalog?.name ?: title).uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        ),
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${state.items.size} items",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }

                // Search within the loaded catalog items.
                Spacer(Modifier.height(10.dp))
                var sFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(
                            if (sFocused) 2.dp else 1.dp,
                            if (sFocused) Color(0xFF818CF8) else Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search, null,
                        tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = catQuery, onValueChange = { catQuery = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF818CF8)),
                            modifier = Modifier.fillMaxWidth().onFocusChanged { sFocused = it.isFocused }
                        )
                        if (catQuery.isEmpty()) Text("Search in this catalog…", color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
                    }
                }

                // Extra filters menu (genre/options/etc.)
                val extrasWithOptions = selectedCatalog
                    ?.extra
                    ?.filter { !it.options.isNullOrEmpty() }
                    .orEmpty()

                if (extrasWithOptions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    // Single horizontally-scrollable filter strip. A "genre" extra
                    // can carry 20-30 options; a fixed Row would overflow the screen
                    // edge and leave the tail chips unreachable, so we flatten every
                    // extra's label + options into one scrolling, DPAD-navigable row.
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        extrasWithOptions.forEach { extra ->
                            item(key = "lbl_${extra.name}") {
                                Text(
                                    text = extra.name.uppercase(),
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    modifier = Modifier.padding(end = 2.dp)
                                )
                            }
                            item(key = "${extra.name}_ALL") {
                                FilterChip(
                                    text = "ALL",
                                    isSelected = state.selectedExtras[extra.name] == null,
                                    onClick = { if (!extra.isRequired) viewModel.setExtraFilter(extra.name, null) }
                                )
                            }
                            items(extra.options.orEmpty(), key = { "${extra.name}_$it" }) { option ->
                                FilterChip(
                                    text = option,
                                    isSelected = state.selectedExtras[extra.name] == option,
                                    onClick = { viewModel.setExtraFilter(extra.name, option) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (state.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading catalog...", color = Color.White.copy(alpha = 0.6f))
                    }
                } else if (state.error != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.error ?: "Failed", color = Color(0xFFF87171))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        // Generous bottom padding so the last rows aren't cropped
                        // by the screen edge / "Loading more" bar.
                        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(shownItems, key = { idx, item -> "${item.id}_$idx" }) { index, item ->
                            CatalogItemCard(item = item) {
                                val resolvedType = item.type.trim()
                                    .ifBlank { selectedCatalog?.type.orEmpty() }
                                    .ifBlank { type.trim() }
                                    .ifBlank { "movie" }
                                // Capture the sibling list so the player can offer an
                                // Up Next + "more from this list" slideshow.
                                com.playtorrio.tv.data.playback.PlaybackQueue.set(
                                    label = "catalog:$addonId:$catalogId",
                                    items = shownItems.map { m ->
                                        val mt = m.type.trim()
                                            .ifBlank { selectedCatalog?.type.orEmpty() }
                                            .ifBlank { type.trim() }
                                            .ifBlank { "movie" }
                                        com.playtorrio.tv.data.playback.PlaybackQueue.Item(
                                            kind = com.playtorrio.tv.data.playback.PlaybackQueue.Kind.ADDON,
                                            title = m.name,
                                            thumbnailUrl = m.poster ?: m.background,
                                            addonId = addonId,
                                            stremioType = mt,
                                            stremioId = m.id,
                                            isMovie = mt == "movie",
                                        )
                                    }
                                )
                                val encodedId = Uri.encode(item.id)
                                navController.navigate("stremio_detail/$addonId/$resolvedType/$encodedId")
                                if (index >= state.items.size - 6 && state.hasMore) {
                                    viewModel.loadMore()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.isLoadingMore) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading more...", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogMenuItem(
    catalog: CatalogDeclaration,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> AccentSecondary.copy(alpha = 0.22f)
                isFocused -> Color.White.copy(alpha = 0.09f)
                else -> Color.Transparent
            }
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = catalog.name.ifBlank { catalog.id },
            color = if (isSelected) AccentSecondary else Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> AccentSecondary.copy(alpha = 0.25f)
                isFocused -> Color.White.copy(alpha = 0.13f)
                else -> Color.Transparent
            }
        ),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = text,
            color = if (isSelected) AccentSecondary else Color.White.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogItemCard(
    item: StremioMetaPreview,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 0.96f,
        animationSpec = tween(180),
        label = "catalogScale"
    )

    Box(
        modifier = Modifier
            .width(220.dp)
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { isFocused = it.isFocused }
                .then(
                    if (isFocused) Modifier.border(
                        width = 1.5.dp,
                        color = AccentSecondary.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(10.dp)
                    ) else Modifier
                ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.35f to Color.Black.copy(alpha = 0.35f),
                                    1f to Color.Black.copy(alpha = 0.92f)
                                )
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
