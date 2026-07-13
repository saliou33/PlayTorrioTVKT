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
                                        if (r.isSuccess) "Addon added" else "Failed: ${r.exceptionOrNull()?.message}",
                                        Toast.LENGTH_SHORT).show()
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
                                    if (r.isSuccess) "Added ${rec.name}" else "Failed: ${r.exceptionOrNull()?.message}",
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
}

@Composable
private fun AddonSection(
    addon: InstalledAddon,
    onCatalog: (type: String, id: String, title: String) -> Unit,
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
        val catalogs = addon.manifest.catalogs
        if (catalogs.isEmpty()) {
            Text("Streams only — no browsable catalogs.", color = Color.White.copy(0.4f), fontSize = 12.sp)
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
