package com.playtorrio.tv.ui.screens.anime

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.*
import android.view.KeyEvent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction

private val Purple = Color(0xFF818CF8)
private val BgDark = Color(0xFF0A0A0F)
private val CardBg = Color(0xFF13131A)
private val Gold   = Color(0xFFFFD700)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeSearchScreen(
    navController: NavController,
    vm: AnimeViewModel = viewModel(),
) {
    // Query lives in the shared VM so back-returning to this screen restores
    // the text + results instead of wiping them.
    val initialQuery = remember { vm.searchQuery.value }
    var query   by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialQuery) }
    var firstRun by remember { mutableStateOf(true) }
    val results by vm.searchResults.collectAsState()
    val loading by vm.searchLoading.collectAsState()

    // Debounce search. On re-entry with an unchanged query + existing results,
    // skip both the refetch and the clear.
    LaunchedEffect(query) {
        vm.searchQuery.value = query
        val isRestore = firstRun && query == initialQuery && vm.searchResults.value.isNotEmpty()
        firstRun = false
        if (query.length >= 2) {
            if (isRestore) return@LaunchedEffect
            kotlinx.coroutines.delay(350)
            vm.search(query)
        } else if (query.isEmpty()) {
            vm.searchResults.value = emptyList()
        }
    }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(Modifier.fillMaxSize()) {

            // ── Search bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgDark.copy(0.98f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back — TV Button so D-pad can reach it
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.size(38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(0.08f),
                        focusedContainerColor = Purple.copy(0.3f),
                        contentColor = Color.White, focusedContentColor = Color.White,
                    ),
                    shape = ButtonDefaults.shape(CircleShape),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))

                // Input field
                val searchFr = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(100)
                    runCatching { searchFr.requestFocus() }
                }
                val focusManager = LocalFocusManager.current
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(0.08f))
                        .border(1.dp, Purple.copy(0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp), tint = Purple.copy(0.7f))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFr)
                                .onKeyEvent { event ->
                                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                                            focusManager.moveFocus(FocusDirection.Down)
                                            return@onKeyEvent true
                                        }
                                    }
                                    false
                                },
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
                            decorationBox = { inner ->
                                if (query.isEmpty()) Text("Search anime...", color = Color.White.copy(0.3f), fontSize = 15.sp)
                                inner()
                            }
                        )
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Close, null,
                                tint = Color.White.copy(0.4f),
                                modifier = Modifier.size(16.dp).clickable { query = "" }
                            )
                        }
                    }
                }
            }

            // ── Loading ───────────────────────────────────────────────────────
            if (loading) {
                val inf = rememberInfiniteTransition(label = "sl")
                val a by inf.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    Text("Searching...", color = Purple.copy(a), fontSize = 14.sp)
                }
            }

            // ── Results grid — focusGroup for D-pad traversal ─────────────────
            if (results.isEmpty() && !loading && query.length >= 2) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.SearchOff, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No results for \"$query\"", color = Color.White.copy(0.4f), fontSize = 14.sp)
                    }
                }
            } else if (results.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.focusGroup(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(results) { anime ->
                        AnimeCard(anime = anime, onClick = {
                            vm.loadDetail(anime)
                            navController.navigate("anime_detail/${anime.id}")
                        })
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (results.isEmpty() && !loading && query.length < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Type to search anime", color = Color.White.copy(0.35f), fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Powered by AniList", color = Purple.copy(0.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
