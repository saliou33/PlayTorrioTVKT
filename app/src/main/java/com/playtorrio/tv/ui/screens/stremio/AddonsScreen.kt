package com.playtorrio.tv.ui.screens.stremio

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.playtorrio.tv.data.stremio.InstalledAddon
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import kotlinx.coroutines.launch

private val Accent = Color(0xFF818CF8)
private val Bg = Color(0xFF0B0B0F)
private val Surface = Color.White.copy(alpha = 0.06f)
private val DangerRed = Color(0xFFF87171)

@Composable
fun AddonsScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addons by remember { mutableStateOf(StremioAddonRepository.getAddons()) }
    var installing by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var configureQrUrl by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = configureQrUrl != null || editMode) {
        if (configureQrUrl != null) configureQrUrl = null else editMode = false
    }

    fun refresh() { addons = StremioAddonRepository.getAddons() }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Addons", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Add, remove and browse your Stremio addons", color = Color.White.copy(0.5f), fontSize = 13.sp)
            }
            // Addons+ → the community addon directory (stremio-addons.net).
            // Installed silently on demand; never listed as a removable addon.
            PillButton(
                text = "Addons+",
                icon = Icons.Filled.Extension,
                accent = Accent,
                enabled = true,
            ) {
                scope.launch {
                    var dir = StremioAddonRepository.getAddons()
                        .firstOrNull { !it.manifest.addonCatalogs.isNullOrEmpty() }
                    if (dir == null) {
                        dir = StremioAddonRepository
                            .installAddon("https://stremio-addons.net/api/manifest.json")
                            .getOrNull()
                        refresh()
                    }
                    val cat = dir?.manifest?.addonCatalogs?.firstOrNull()
                    if (dir != null && cat != null) {
                        navController.navigate(
                            "addon_directory/${Uri.encode(dir.manifest.id)}/${Uri.encode(cat.type)}/" +
                                "${Uri.encode(cat.id)}/${Uri.encode("Addons+")}"
                        )
                    } else {
                        Toast.makeText(context, "Addon directory unavailable right now", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            PillButton(
                text = if (installing) "Adding…" else "Add recommended",
                icon = Icons.Filled.Add,
                accent = Accent,
                enabled = !installing,
            ) {
                installing = true
                scope.launch {
                    val n = StremioAddonRepository.installRecommended()
                    refresh(); installing = false
                    Toast.makeText(context, "Added $n addon(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Add by manifest URL
        var boxFocused by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface)
                    .border(if (boxFocused) 2.dp else 1.dp, if (boxFocused) Accent else Color.White.copy(0.12f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val u = urlInput.trim()
                            if (u.isNotBlank()) {
                                scope.launch {
                                    val r = StremioAddonRepository.installAddon(u)
                                    refresh()
                                    Toast.makeText(context,
                                        if (r.isSuccess) "Addon added"
                                        else (r.exceptionOrNull()?.message ?: "Couldn't add — check the manifest URL"),
                                        Toast.LENGTH_LONG).show()
                                    if (r.isSuccess) urlInput = ""
                                }
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { boxFocused = it.isFocused }
                    )
                    if (urlInput.isEmpty()) {
                        Text("Paste a manifest URL (…/manifest.json)", color = Color.White.copy(0.3f), fontSize = 15.sp)
                    }
                }
            }
        }

        // (The old "Suggested addons" row was removed — the Addons+ directory
        //  button covers discovery with search + filters over 500+ addons.)
        Spacer(Modifier.height(20.dp))

        // Directory-only addons (addon_catalog resource, no content catalogs) are
        // special — reached via the Addons+ button, never listed as removable.
        val listedAddons = addons.filter {
            it.manifest.addonCatalogs.isNullOrEmpty() || it.manifest.catalogs.isNotEmpty()
        }
        if (listedAddons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No addons yet. Tap “Add recommended” to install Torrentio & more.",
                    color = Color.White.copy(0.5f), fontSize = 14.sp)
            }
        } else {
            // Card grid: click opens the addon's first catalog directly (the
            // catalog screen's sidebar lists the rest). The Edit card toggles
            // remove mode — same gesture as the Continue Watching row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Installed", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                if (editMode) {
                    Text("select an addon to remove", color = DangerRed, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item(key = "__edit__") {
                    AddonEditCard(active = editMode) { editMode = !editMode }
                }
                items(listedAddons.size, key = { listedAddons[it].manifest.id }) { i ->
                    val addon = listedAddons[i]
                    AddonCard(
                        addon = addon,
                        editMode = editMode,
                        onClick = {
                            if (editMode) {
                                scope.launch {
                                    StremioAddonRepository.removeAddon(addon.manifest.id)
                                    refresh()
                                    if (StremioAddonRepository.getAddons().isEmpty()) editMode = false
                                    Toast.makeText(context, "Removed ${addon.manifest.name}", Toast.LENGTH_SHORT).show()
                                }
                                return@AddonCard
                            }
                            val cat = addon.manifest.catalogs.firstOrNull()
                            val dirCat = addon.manifest.addonCatalogs?.firstOrNull()
                            when {
                                cat != null -> navController.navigate(
                                    "stremio_catalog/${Uri.encode(addon.manifest.id)}/${Uri.encode(cat.type)}/" +
                                        "${Uri.encode(cat.id)}/${Uri.encode(addon.manifest.name)}"
                                )
                                dirCat != null -> navController.navigate(
                                    "addon_directory/${Uri.encode(addon.manifest.id)}/${Uri.encode(dirCat.type)}/" +
                                        "${Uri.encode(dirCat.id)}/${Uri.encode(addon.manifest.name)}"
                                )
                                addon.manifest.behaviorHints?.configurable == true ->
                                    configureQrUrl = "${addon.transportUrl}/configure"
                                else -> Toast.makeText(
                                    context,
                                    "${addon.manifest.name} provides streams only — used automatically during playback",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }

    // ── Configure-on-phone QR dialog ─────────────────────────────────────────
    configureQrUrl?.let { url ->
        val qr = remember(url) {
            runCatching { com.playtorrio.tv.server.QrCodeGenerator.generate(url, 400) }.getOrNull()
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(0.85f))
                .clickable { configureQrUrl = null },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0F0F1F))
                    .border(1.dp, Color.White.copy(0.12f), RoundedCornerShape(18.dp))
                    .padding(28.dp)
            ) {
                Text("SET UP ON YOUR PHONE", color = Accent, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This addon needs configuration. Scan to open its setup page,\nthen paste the manifest URL it gives you into the field above.",
                    color = Color.White.copy(0.6f), fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                if (qr != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Configure QR",
                        modifier = Modifier.size(200.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Text(url, color = Accent, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(url, color = Color.White.copy(0.4f), fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Text("Press Back to close", color = Color.White.copy(0.35f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AddonEditCard(active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) DangerRed.copy(0.18f) else Surface)
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> if (active) DangerRed else Accent
                    active -> DangerRed.copy(0.6f)
                    else -> Color.White.copy(0.1f)
                },
                RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Icon(
            Icons.Filled.Delete, null,
            tint = if (active) DangerRed else Color.White.copy(0.7f),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (active) "Done" else "Edit",
            color = if (active) DangerRed else Color.White.copy(0.8f),
            fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (active) "exit remove mode" else "remove addons",
            color = Color.White.copy(0.45f), fontSize = 10.sp
        )
    }
}

@Composable
private fun AddonCard(addon: InstalledAddon, editMode: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val m = addon.manifest
    val subtitle = when {
        m.catalogs.isNotEmpty() -> {
            val types = m.catalogs.map { it.type }.distinct().take(2).joinToString("/")
            "${m.catalogs.size} catalog${if (m.catalogs.size != 1) "s" else ""} · $types"
        }
        !m.addonCatalogs.isNullOrEmpty() -> "Addon directory"
        m.behaviorHints?.configurable == true -> "Needs setup"
        else -> "Streams only"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White.copy(0.12f) else Surface)
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    editMode -> DangerRed.copy(if (focused) 1f else 0.5f)
                    focused -> Accent
                    else -> Color.White.copy(0.1f)
                },
                RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (!m.logo.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = m.logo,
                    contentDescription = m.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            } else {
                Icon(Icons.Filled.Extension, null, tint = Accent, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            m.name,
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (editMode) "click to remove" else subtitle,
            color = if (editMode) DangerRed else Color.White.copy(0.5f),
            fontSize = 10.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}


@Composable
private fun PillButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) accent.copy(0.35f) else accent.copy(0.15f))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else accent.copy(0.5f), RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
