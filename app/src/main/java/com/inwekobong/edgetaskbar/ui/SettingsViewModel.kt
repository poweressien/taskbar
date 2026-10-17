package com.inwekobong.edgetaskbar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository.get(app)
    private val catalog = AppCatalog.get(app)

    val settings: StateFlow<TaskbarSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TaskbarSettings())

    fun update(transform: (TaskbarSettings) -> TaskbarSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun clearRecents(onDone: () -> Unit) {
        viewModelScope.launch { repo.clearRecents(); onDone() }
    }

    fun removeStalePins(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val apps = catalog.load(true)
            val removed = if (apps.isEmpty()) 0 else repo.retainPinned(catalog.validPackages())
            onDone(removed)
        }
    }
}
