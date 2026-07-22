package com.playtorrio.tv.ui.screens.stremio

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.playtorrio.tv.data.stremio.AddonCatalogEntry
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import com.playtorrio.tv.data.stremio.StremioService
import kotlinx.coroutines.launch

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)
private val Ok = Color(0xFF4ADE80)

/**
 * Browses an addon directory (Stremio "addon_catalog" resource — e.g. the
 * stremio-addons.net community list of 500+ addons) with search + type filters,
 * and installs entries directly from the app.
 */
@Composable
fun AddonDirectoryScreen(
    addonId: String,
    type: String,
    catalogId: String,
    title: String,
    navController: NavController,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var entries by remember { mutableStateOf<List<AddonCatalogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("All") }
    var sortMode by remember { mutableStateOf("Featured") }
    var browsableOnly by remember { mutableStateOf(false) }
    var noSetupOnly by remember { mutableStateOf(false) }
    // Curated rank from the official Stremio collection (lower = more featured).
    var officialRank by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var installedIds by remember {
        mutableStateOf(StremioAddonRepository.getAddons().map { it.manifest.id }.toSet())
    }
    var installingUrl by remember { mutableStateOf<String?>(null) }

    fun normUrl(u: String) = u.removeSuffix("/manifest.json").trimEnd('/')

    LaunchedEffect(addonId, type, catalogId) {
        loading = true
        val addon = StremioAddonRepository.getAddons().firstOrNull { it.manifest.id == addonId }
        entries = if (addon != null) StremioService.getAddonCatalog(addon, type, catalogId) else emptyList()
        officialRank = runCatching {
            StremioService.getOfficialCollectionUrls().withIndex().associate { (i, u) -> u to i }
        }.getOrDefault(emptyMap())
        loading = false
    }

    // Distinct content types across the directory, for the filter strip.
    val availableTypes = remember(entries) {
        listOf("All") + entries.flatMap { it.manifest.types }.map { it.lowercase() }.distinct().sorted()
    }
    val shown = remember(entries, query, typeFilter, sortMode, browsableOnly, noSetupOnly, officialRank) {
        entries.filter { e ->
            (typeFilter == "All" || e.manifest.types.any { it.equals(typeFilter, ignoreCase = true) }) &&
                (!browsableOnly || e.manifest.catalogs.isNotEmpty()) &&
                (!noSetupOnly || e.manifest.behaviorHints?.configurationRequired != true) &&
                (query.isBlank() ||
                    e.manifest.name.contains(query.trim(), ignoreCase = true) ||
                    e.manifest.description.contains(query.trim(), ignoreCase = true))
        }.let { list ->
            when (sortMode) {
                // Official-collection members first (in curated order), then A–Z.
                "Featured" -> list.sortedWith(
                    compareBy(
                        { officialRank[normUrl(it.transportUrl)] ?: Int.MAX_VALUE },
                        { it.manifest.name.lowercase() }
                    )
                )
                else -> list.sortedBy { it.manifest.name.lowercase() }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (loading) "Loading directory…" else "${shown.size} of ${entries.size} addons",
                    color = Color.White.copy(0.5f), fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search box
        var sFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Surface)
                .border(if (sFocused) 2.dp else 1.dp, if (sFocused) Accent else Color.White.copy(0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White.copy(0.6f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { sFocused = it.isFocused }
                )
                if (query.isEmpty()) Text("Search addons…", color = Color.White.copy(0.3f), fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Type filter strip
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(availableTypes) { t ->
                FilterPill(
                    label = t.replaceFirstChar { it.uppercase() },
                    selected = typeFilter.equals(t, ignoreCase = true),
                ) { typeFilter = t }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Sort + capability filters — no public download counts exist, so
        // "Featured" ranks by the official Stremio collection's curated order.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterPill("★ Featured", sortMode == "Featured") { sortMode = "Featured" } }
            item { FilterPill("A–Z", sortMode == "A–Z") { sortMode = "A–Z" } }
            item { FilterPill("Browsable", browsableOnly) { browsableOnly = !browsableOnly } }
            item { FilterPill("No setup", noSetupOnly) { noSetupOnly = !noSetupOnly } }
        }

        Spacer(Modifier.height(14.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Directory unavailable — the addon didn't return a list.", color = Color.White.copy(0.5f))
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing matches these filters.", color = Color.White.copy(0.5f))
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(shown, key = { it.transportUrl }) { entry ->
                    DirectoryRow(
                        entry = entry,
                        featured = officialRank.containsKey(normUrl(entry.transportUrl)),
                        installed = entry.manifest.id in installedIds,
                        installing = installingUrl == entry.transportUrl,
                        onInstall = {
                            installingUrl = entry.transportUrl
                            scope.launch {
                                val r = StremioAddonRepository.installAddon(entry.transportUrl)
                                installingUrl = null
                                if (r.isSuccess) {
                                    installedIds = installedIds + entry.manifest.id
                                    Toast.makeText(context, "Added ${entry.manifest.name}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        r.exceptionOrNull()?.message ?: "Couldn't add ${entry.manifest.name}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (selected) Accent else Color.White.copy(0.75f),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent.copy(0.2f) else Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.12f), RoundedCornerShape(999.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun DirectoryRow(
    entry: AddonCatalogEntry,
    featured: Boolean,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val m = entry.manifest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(0.12f) else Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.1f), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = !installed && !installing) { onInstall() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = m.logo,
            contentDescription = m.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(0.05f))
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (featured) {
                    Text(
                        "★ FEATURED",
                        color = Color(0xFFFFD700),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFD700).copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            if (m.description.isNotBlank()) {
                Text(m.description, color = Color.White.copy(0.55f), fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (m.types.isNotEmpty()) {
                Text(m.types.joinToString(" · "), color = Accent.copy(0.8f), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            installing -> CircularProgressIndicator(color = Accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = Ok, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Installed", color = Ok, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Install", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
