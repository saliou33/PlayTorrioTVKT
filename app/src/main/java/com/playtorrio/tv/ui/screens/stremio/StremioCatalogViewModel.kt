package com.playtorrio.tv.ui.screens.stremio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playtorrio.tv.data.stremio.CatalogDeclaration
import com.playtorrio.tv.data.stremio.InstalledAddon
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import com.playtorrio.tv.data.stremio.StremioMetaPreview
import com.playtorrio.tv.data.stremio.StremioService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class StremioCatalogUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val addonName: String = "",
    val catalogs: List<CatalogDeclaration> = emptyList(),
    val selectedCatalogIndex: Int = 0,
    val selectedExtras: Map<String, String> = emptyMap(),
    val items: List<StremioMetaPreview> = emptyList(),
    val hasMore: Boolean = true
)

class StremioCatalogViewModel(
    /** Survives activity/process recreation (low-RAM TVs kill the main activity
     *  while PlayerActivity is foreground) — used to restore the selected
     *  catalog + genre filters when the in-memory VM didn't survive. */
    private val saved: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StremioCatalogUiState())
    val uiState: StateFlow<StremioCatalogUiState> = _uiState

    private var currentAddon: InstalledAddon? = null
    private var currentType: String = "movie"
    private var currentCatalogId: String = ""
    private var currentCatalogs: List<CatalogDeclaration> = emptyList()
    private var currentExtras: Map<String, String> = emptyMap()
    private var loadedRouteKey: String? = null

    fun load(addonId: String, type: String, catalogId: String) {
        // The screen's LaunchedEffect re-runs on every back-return (the
        // composable re-enters composition), but this VM survives on the back
        // stack. Reloading here would wipe the user's selected catalog + genre
        // filters and refetch — skip when we already hold this route's data.
        val routeKey = "$addonId|$type|$catalogId"
        if (routeKey == loadedRouteKey && _uiState.value.items.isNotEmpty()) return
        loadedRouteKey = routeKey

        val addon = StremioAddonRepository.getAddons().firstOrNull { it.manifest.id == addonId }
            ?: run {
                _uiState.value = StremioCatalogUiState(
                    isLoading = false,
                    error = "Addon not found",
                    hasMore = false
                )
                return
            }

        currentAddon = addon
        val sameTypeCatalogs = addon.manifest.catalogs.filter { it.type.equals(type, ignoreCase = true) }
        currentCatalogs = if (sameTypeCatalogs.isNotEmpty()) sameTypeCatalogs else addon.manifest.catalogs

        if (currentCatalogs.isEmpty()) {
            _uiState.value = StremioCatalogUiState(
                isLoading = false,
                error = "No catalogs available",
                addonName = addon.manifest.name,
                hasMore = false
            )
            return
        }

        val initialIndex = currentCatalogs.indexOfFirst { it.id == catalogId }.let {
            if (it >= 0) it else 0
        }

        // If a previous VM instance for this exact route saved its selection into
        // the state bundle (activity/process recreated underneath us), restore
        // the catalog tab + extra filters instead of resetting to defaults.
        val savedRoute: String? = saved["route"]
        val savedIdx: Int? = saved["sel_idx"]
        @Suppress("UNCHECKED_CAST")
        val savedExtras = saved.get<java.io.Serializable>("extras") as? HashMap<String, String>
        saved["route"] = routeKey
        val restoreIdx = if (savedRoute == routeKey && savedIdx != null && savedIdx in currentCatalogs.indices)
            savedIdx else initialIndex
        val restoreExtras = if (savedRoute == routeKey) savedExtras else null

        _uiState.value = StremioCatalogUiState(
            isLoading = true,
            addonName = addon.manifest.name,
            catalogs = currentCatalogs,
            selectedCatalogIndex = restoreIdx
        )

        selectCatalog(restoreIdx, restoreExtras)
    }

    fun selectCatalog(index: Int, restoreExtras: Map<String, String>? = null) {
        val addon = currentAddon ?: return
        val catalog = currentCatalogs.getOrNull(index) ?: return

        currentType = catalog.type
        currentCatalogId = catalog.id
        currentExtras = restoreExtras ?: buildDefaultExtras(catalog)
        saved["sel_idx"] = index
        saved["extras"] = HashMap(currentExtras)

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                selectedCatalogIndex = index,
                selectedExtras = currentExtras,
                items = emptyList(),
                hasMore = true
            )

            val first = StremioService.getCatalog(
                addon = addon,
                type = currentType,
                catalogId = currentCatalogId,
                extra = currentExtras
            )?.metas ?: emptyList()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                items = first,
                hasMore = first.isNotEmpty()
            )
        }
    }

    fun setExtraFilter(name: String, value: String?) {
        val addon = currentAddon ?: return
        val state = _uiState.value
        val selectedCatalog = state.catalogs.getOrNull(state.selectedCatalogIndex) ?: return

        val mutable = currentExtras.toMutableMap()
        if (value.isNullOrBlank()) mutable.remove(name) else mutable[name] = value
        currentExtras = mutable
        saved["extras"] = HashMap(currentExtras)

        viewModelScope.launch {
            _uiState.value = state.copy(
                isLoading = true,
                selectedExtras = currentExtras,
                items = emptyList(),
                hasMore = true,
                error = null
            )

            val first = StremioService.getCatalog(
                addon = addon,
                type = selectedCatalog.type,
                catalogId = selectedCatalog.id,
                extra = currentExtras
            )?.metas ?: emptyList()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                items = first,
                hasMore = first.isNotEmpty()
            )
        }
    }

    fun loadMore() {
        val addon = currentAddon ?: return
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoadingMore = true)
            val skip = state.items.size
            val page = StremioService.getCatalog(
                addon = addon,
                type = currentType,
                catalogId = currentCatalogId,
                extra = currentExtras + mapOf("skip" to skip.toString())
            )?.metas ?: emptyList()

            // Many addons ignore the skip parameter and return the same first
            // page again — appending blindly duplicated the whole list and kept
            // "loading more" alive forever. De-dupe by id; nothing new = the end.
            val seen = state.items.mapTo(HashSet()) { it.id }
            val fresh = page.filter { it.id !in seen }
            _uiState.value = _uiState.value.copy(
                isLoadingMore = false,
                items = state.items + fresh,
                hasMore = fresh.isNotEmpty()
            )
        }
    }

    private fun buildDefaultExtras(catalog: CatalogDeclaration): Map<String, String> {
        val requiredNames = buildSet {
            addAll(catalog.extra.filter { it.isRequired }.map { it.name })
            addAll(catalog.extraRequired.orEmpty())
        }

        return requiredNames.mapNotNull { name ->
            val option = catalog.extra.firstOrNull { it.name == name }?.options?.firstOrNull()
            option?.let { name to it }
        }.toMap()
    }
}
