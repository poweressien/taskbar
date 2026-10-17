package com.inwekobong.edgetaskbar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.AppEntry
import com.inwekobong.edgetaskbar.data.ListOps
import com.inwekobong.edgetaskbar.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PickerItem(val app: AppEntry, val pinned: Boolean)

data class PickerState(
    val loading: Boolean = true,
    val items: List<PickerItem> = emptyList(),
    val pinnedCount: Int = 0,
    val pinnedMode: Boolean = false,
    val query: String = "",
)

class AppPickerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository.get(app)
    private val catalog = AppCatalog.get(app)

    private val apps = MutableStateFlow<List<AppEntry>?>(null)
    val query = MutableStateFlow("")
    val pinnedMode = MutableStateFlow(false)

    val state: StateFlow<PickerState> = combine(apps, repo.pinned, query, pinnedMode) { all, pins, q, mode ->
        if (all == null) return@combine PickerState()
        val byPkg = all.associateBy { it.packageName }
        val base = if (mode) pins.mapNotNull { byPkg[it] } else all
        val shown = ListOps.filterByLabel(base, q) { it.label }
        PickerState(
            loading = false,
            items = shown.map { PickerItem(it, it.packageName in pins) },
            pinnedCount = pins.size,
            pinnedMode = mode,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickerState())

    init {
        viewModelScope.launch { apps.value = catalog.load() }
    }

    fun toggle(pkg: String, pinned: Boolean) {
        viewModelScope.launch { if (pinned) repo.unpin(pkg) else repo.pin(pkg) }
    }

    fun unpin(pkg: String) { viewModelScope.launch { repo.unpin(pkg) } }

    fun pinAt(index: Int, pkg: String) { viewModelScope.launch { repo.pinAt(index, pkg) } }

    /** Saves a new order for the pinned apps that are visible; hidden (uninstalled) pins stay at the end. */
    fun reorder(visibleOrder: List<String>, currentPins: List<String>) {
        viewModelScope.launch {
            repo.setPinned(visibleOrder + currentPins.filter { it !in visibleOrder })
        }
    }
}
