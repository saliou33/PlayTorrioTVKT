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

    androidx.activity.compose.BackHandler(enabled = configureQrUrl != null) { configureQrUrl = null }

    fun refresh() { addons = StremioAddonRepository.getAddons() }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Addons", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Add, remove and browse your Stremio addons", color = Color.White.copy(0.5f), fontSize = 13.sp)
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

        Spacer(Modifier.height(18.dp))

        // Suggested addons discovery row
        Text("Suggested addons", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(StremioAddonRepository.RECOMMENDED_ADDONS) { rec ->
                var focused by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (focused) Color.White.copy(0.12f) else Surface)
                        .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.1f), RoundedCornerShape(12.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .clickable {
                            scope.launch {
                                val r = StremioAddonRepository.installAddon(rec.manifestUrl)
                                refresh()
                                Toast.makeText(context,
                                    if (r.isSuccess) "Added ${rec.name}" else "Couldn't add ${rec.name} — unavailable",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rec.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.Add, null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(rec.description, color = Color.White.copy(0.55f), fontSize = 11.sp, maxLines = 3)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (addons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No addons yet. Tap “Add recommended” to install Torrentio & more.",
                    color = Color.White.copy(0.5f), fontSize = 14.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(addons) { addon ->
                    AddonSection(
                        addon = addon,
                        onCatalog = { type, catalogId, title ->
                            navController.navigate(
                                "stremio_catalog/${Uri.encode(addon.manifest.id)}/${Uri.encode(type)}/" +
                                    "${Uri.encode(catalogId)}/${Uri.encode(title)}"
                            )
                        },
                        onAddonDirectory = { type, catalogId, title ->
                            navController.navigate(
                                "addon_directory/${Uri.encode(addon.manifest.id)}/${Uri.encode(type)}/" +
                                    "${Uri.encode(catalogId)}/${Uri.encode(title)}"
                            )
                        },
                        onConfigure = { configureQrUrl = "${addon.transportUrl}/configure" },
                        onRemove = {
                            scope.launch {
                                StremioAddonRepository.removeAddon(addon.manifest.id)
                                refresh()
                                Toast.makeText(context, "Removed ${addon.manifest.name}", Toast.LENGTH_SHORT).show()
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
private fun AddonSection(
    addon: InstalledAddon,
    onCatalog: (type: String, id: String, title: String) -> Unit,
    onAddonDirectory: (type: String, id: String, title: String) -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Extension, null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(addon.manifest.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            PillButton(text = "Remove", icon = Icons.Filled.Delete, accent = DangerRed, enabled = true, onClick = onRemove)
        }
        Spacer(Modifier.height(10.dp))

        // Addon directories (catalogs of OTHER addons, e.g. stremio-addons.net).
        val addonCats = addon.manifest.addonCatalogs.orEmpty()
        if (addonCats.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(addonCats) { cat ->
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Accent.copy(0.3f) else Accent.copy(0.12f))
                            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Accent.copy(0.4f), RoundedCornerShape(10.dp))
                            .onFocusChanged { focused = it.isFocused }
                            .clickable {
                                onAddonDirectory(cat.type, cat.id, "${addon.manifest.name} · ${cat.name.ifBlank { cat.id }}")
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Browse addons · ${cat.name.ifBlank { cat.id }}",
                            color = if (focused) Color.White else Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val catalogs = addon.manifest.catalogs
        val configurable = addon.manifest.behaviorHints?.configurable == true
        if (catalogs.isEmpty() && addonCats.isEmpty()) {
            if (configurable) {
                // e.g. Comet / MediaFusion / AIOStreams public instances: the base
                // manifest has no catalogs until configured per-user.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Needs setup to unlock catalogs/streams.", color = Color.White.copy(0.5f), fontSize = 12.sp)
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) Accent.copy(0.35f) else Accent.copy(0.15f))
                            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Accent.copy(0.5f), RoundedCornerShape(8.dp))
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onConfigure() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Set up (QR)", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text("Streams only — no browsable catalogs.", color = Color.White.copy(0.4f), fontSize = 12.sp)
            }
        } else if (catalogs.isEmpty()) {
            // Directory-only addon (handled above) — nothing more to show.
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(catalogs) { cat ->
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Color.White.copy(0.15f) else Surface)
                            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color.White.copy(0.1f), RoundedCornerShape(10.dp))
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onCatalog(cat.type, cat.id, "${addon.manifest.name} · ${cat.name.ifBlank { cat.id }}") }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("${cat.name.ifBlank { cat.id }} · ${cat.type}",
                            color = if (focused) Color.White else Color.White.copy(0.85f), fontSize = 13.sp)
                    }
                }
            }
        }
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
