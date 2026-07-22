package com.playtorrio.tv.ui.screens.detail

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.playtorrio.tv.PlayerActivity
import com.playtorrio.tv.data.AppPreferences
import com.playtorrio.tv.data.streaming.StreamExtractorService
import com.playtorrio.tv.data.streaming.StreamResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamingSplash(
    visible: Boolean,
    backdropUrl: String?,
    logoUrl: String?,
    title: String,
    year: String?,
    rating: String?,
    overview: String?,
    isMovie: Boolean,
    tmdbId: Int,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    onDismiss: () -> Unit,
    posterUrl: String? = null,
    imdbId: String? = null,
    forceSourceIndex: Int? = null,
    resumePositionMs: Long? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(200))
    ) {
        val context = LocalContext.current
        var statusText by remember { mutableStateOf("Getting sources for you…") }
        var isError by remember { mutableStateOf(false) }

        LaunchedEffect(visible, tmdbId, seasonNumber, episodeNumber) {
            if (!visible) return@LaunchedEffect
            isError = false
            statusText = "Finding best source…"

            // Open a diagnostics session so the /diag phone page shows which
            // sources get tried for this title and why each one fails.
            val diagLabel = buildString {
                append(title)
                if (seasonNumber != null && episodeNumber != null) {
                    append(" S%02dE%02d".format(seasonNumber, episodeNumber))
                }
                year?.let { append(" ($it)") }
            }
            com.playtorrio.tv.data.streaming.StreamDiagnostics.startSession(diagLabel)

            // Race sources in batches of 2 to keep WebView load light on TV boxes.
            // First batch in priority gets paired together; whichever returns a
            // stream first wins (the slower one is cancelled).
            var winner: Pair<StreamResult, StreamExtractorService.Source>? = null
            val priorityOrder = buildList {
                // If resuming from a remembered source, try it first.
                forceSourceIndex?.let { add(it) }
                addAll(AppPreferences.streamingSourceOrder)
            }.distinct() // User-configured priority (Settings → Streaming Sources)
            val orderedSources = buildList {
                priorityOrder.forEach { idx ->
                    StreamExtractorService.SOURCES.find { it.index == idx }?.let { add(it) }
                }
                StreamExtractorService.SOURCES
                    .filterNot { src -> priorityOrder.contains(src.index) }
                    .forEach { add(it) }
            }

            val batches = orderedSources.chunked(2)
            for ((batchIdx, batch) in batches.withIndex()) {
                val names = batch.joinToString(" + ") { it.name }
                statusText = "Trying $names (${batchIdx + 1}/${batches.size})…"

                val batchWinner: Pair<StreamResult, StreamExtractorService.Source>? = coroutineScope {
                    val deferreds = batch.map { source ->
                        async {
                            val r = StreamExtractorService.extract(
                                context = context,
                                sourceIdx = source.index,
                                tmdbId = tmdbId,
                                season = seasonNumber,
                                episode = episodeNumber,
                                timeoutMs = AppPreferences.streamingExtractTimeoutSec * 1000L
                            )
                            r?.let { it to source }
                        }
                    }
                    // Wait for the first non-null; cancel the rest.
                    var found: Pair<StreamResult, StreamExtractorService.Source>? = null
                    val remaining = deferreds.toMutableList()
                    while (remaining.isNotEmpty() && found == null) {
                        val done = kotlinx.coroutines.selects.select<Pair<StreamResult, StreamExtractorService.Source>?> {
                            remaining.forEach { d ->
                                d.onAwait { it }
                            }
                        }
                        if (done != null) {
                            found = done
                        } else {
                            // Remove the completed (null) deferred and keep waiting.
                            val completed = remaining.indexOfFirst { it.isCompleted }
                            if (completed >= 0) remaining.removeAt(completed) else remaining.clear()
                        }
                    }
                    if (found != null) {
                        deferreds.forEach { if (!it.isCompleted) it.cancel() }
                    }
                    found
                }

                if (batchWinner != null) {
                    winner = batchWinner
                    break
                }

                // Small breathing room for UI/frame scheduling between heavy attempts.
                delay(120)
            }

            if (winner != null) {
                val (streamResult, source) = winner
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra("streamUrl", streamResult.url)
                    putExtra("streamReferer", streamResult.referer)
                    putExtra("sourceIndex", source.index)
                    putExtra("title", title)
                    putExtra("logoUrl", logoUrl)
                    putExtra("backdropUrl", backdropUrl)
                    putExtra("year", year)
                    putExtra("rating", rating)
                    putExtra("overview", overview)
                    putExtra("isMovie", isMovie)
                    seasonNumber?.let { putExtra("seasonNumber", it) }
                    episodeNumber?.let { putExtra("episodeNumber", it) }
                    putExtra("episodeTitle", episodeTitle)
                    putExtra("tmdbId", tmdbId)
                    posterUrl?.let { putExtra("posterUrl", it) }
                    imdbId?.let { putExtra("imdbId", it) }
                    resumePositionMs?.let { putExtra("resumePositionMs", it) }
                }
                context.startActivity(intent)
                onDismiss()
            } else {
                // All extractor sources failed — before giving up, fall back to
                // the installed Stremio addons (NoTorrent, Torrentio, …) for this
                // title and play the first usable stream.
                statusText = "Checking addon streams…"
                val played = runCatching {
                    playFirstAddonStream(
                        context = context,
                        tmdbId = tmdbId, isMovie = isMovie, imdbIdHint = imdbId,
                        seasonNumber = seasonNumber, episodeNumber = episodeNumber,
                        title = title, logoUrl = logoUrl, backdropUrl = backdropUrl,
                        posterUrl = posterUrl, year = year, rating = rating,
                        overview = overview, episodeTitle = episodeTitle,
                        resumePositionMs = resumePositionMs,
                    )
                }.getOrDefault(false)
                if (played) {
                    onDismiss()
                } else {
                    statusText = "Could not find a stream. Please try again."
                    isError = true
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onDismiss(); true
                    } else false
                }
        ) {
            // Dimmed backdrop
            backdropUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = 0.6f))
                        },
                    contentScale = ContentScale.Crop
                )
            }

            // Center content: logo / title → spinner → status
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (logoUrl != null) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = title,
                        modifier = Modifier
                            .height(72.dp)
                            .padding(horizontal = 24.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(36.dp))

                if (!isError) {
                    CircularProgressIndicator(
                        color = Color(0xFF818CF8),
                        modifier = Modifier.size(42.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(20.dp))
                }

                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = if (isError) Color(0xFFF87171) else Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Last-resort fallback when every extractor source fails: query the installed
 * Stremio addons for this title (episode-aware for series) and launch the first
 * playable stream. Returns false when no addon has anything either.
 */
private suspend fun playFirstAddonStream(
    context: android.content.Context,
    tmdbId: Int,
    isMovie: Boolean,
    imdbIdHint: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    title: String,
    logoUrl: String?,
    backdropUrl: String?,
    posterUrl: String?,
    year: String?,
    rating: String?,
    overview: String?,
    episodeTitle: String?,
    resumePositionMs: Long?,
): Boolean {
    val api = com.playtorrio.tv.data.api.TmdbClient.api
    val key = com.playtorrio.tv.data.api.TmdbClient.API_KEY
    val imdb = imdbIdHint?.takeIf { it.startsWith("tt") }
        ?: runCatching {
            if (isMovie) api.getMovieExternalIds(tmdbId, key).imdbId
            else api.getTvExternalIds(tmdbId, key).imdbId
        }.getOrNull()?.takeIf { it.startsWith("tt") }
        ?: return false

    val sid = if (!isMovie && seasonNumber != null && episodeNumber != null)
        "$imdb:$seasonNumber:$episodeNumber" else imdb
    val type = if (isMovie) "movie" else "series"
    val addons = com.playtorrio.tv.data.stremio.StremioAddonRepository.getAddons()
    val streams = com.playtorrio.tv.data.stremio.StremioService.getStreams(addons, type, sid)

    fun baseIntent() = Intent(context, PlayerActivity::class.java).apply {
        putExtra("title", title)
        putExtra("logoUrl", logoUrl)
        putExtra("backdropUrl", backdropUrl)
        putExtra("posterUrl", posterUrl)
        putExtra("year", year)
        putExtra("rating", rating)
        putExtra("overview", overview)
        putExtra("isMovie", isMovie)
        seasonNumber?.let { putExtra("seasonNumber", it) }
        episodeNumber?.let { putExtra("episodeNumber", it) }
        putExtra("episodeTitle", episodeTitle)
        putExtra("tmdbId", tmdbId)
        putExtra("imdbId", imdb)
        putExtra("stremioType", type)
        putExtra("stremioId", sid)
        resumePositionMs?.let { putExtra("resumePositionMs", it) }
    }

    for (stream in streams) {
        when (val route = com.playtorrio.tv.data.stremio.StremioService.routeStream(stream)) {
            is com.playtorrio.tv.data.stremio.StreamRoute.DirectUrl -> {
                context.startActivity(baseIntent().apply {
                    putExtra("streamUrl", route.url)
                    putExtra("streamReferer", route.headers?.get("Referer") ?: "")
                    stream.addonId?.let { putExtra("addonId", it) }
                    (stream.url ?: stream.infoHash)?.let { putExtra("streamPickKey", it) }
                    (stream.name ?: stream.title)?.let { putExtra("streamPickName", it) }
                })
                return true
            }
            is com.playtorrio.tv.data.stremio.StreamRoute.Torrent -> {
                context.startActivity(baseIntent().apply {
                    putExtra("magnetUri", route.magnet)
                    route.fileIdx?.let { putExtra("fileIdx", it) }
                    stream.addonId?.let { putExtra("addonId", it) }
                    (stream.url ?: stream.infoHash)?.let { putExtra("streamPickKey", it) }
                    (stream.name ?: stream.title)?.let { putExtra("streamPickName", it) }
                })
                return true
            }
            else -> continue
        }
    }
    return false
}
