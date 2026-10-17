package com.inwekobong.edgetaskbar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.overlay.OverlayService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository.get(app)

    val settings: StateFlow<TaskbarSettings?> = repo.settings
        .map<TaskbarSettings, TaskbarSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pinnedCount: StateFlow<Int> = repo.pinned
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Saves "enabled" first, then starts the service, so the service never sees a stale value. */
    fun start() {
        viewModelScope.launch {
            repo.update { it.copy(enabled = true, temporarilyHidden = false) }
            try {
                OverlayService.start(getApplication())
            } catch (_: Exception) {
                repo.update { it.copy(enabled = false) }
            }
        }
    }

    fun stop() {
        viewModelScope.launch { repo.update { it.copy(enabled = false) } }
    }

    fun setNavTop(on: Boolean) {
        viewModelScope.launch { repo.update { it.copy(navTop = on) } }
    }
}
