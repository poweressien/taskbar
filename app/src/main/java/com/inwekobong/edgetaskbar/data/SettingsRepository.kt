package com.inwekobong.edgetaskbar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "edge_taskbar")

class SettingsRepository private constructor(private val store: DataStore<Preferences>) {

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val edge = stringPreferencesKey("edge")
        val align = stringPreferencesKey("align")
        val iconSize = intPreferencesKey("icon_size")
        val spacing = intPreferencesKey("spacing")
        val padding = intPreferencesKey("padding")
        val radius = intPreferencesKey("radius")
        val handleLen = intPreferencesKey("handle_len")
        val maxIcons = intPreferencesKey("max_icons")
        val opacity = intPreferencesKey("opacity")
        val theme = stringPreferencesKey("theme")
        val collapseAfterLaunch = booleanPreferencesKey("collapse_after_launch")
        val autoCollapse = intPreferencesKey("auto_collapse_s")
        val hideOnKeyboard = booleanPreferencesKey("hide_on_keyboard")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val handleOffset = floatPreferencesKey("handle_offset")
        val hidden = booleanPreferencesKey("temp_hidden")
        val pinned = stringPreferencesKey("pinned") // newline-separated package names, in order
        val recents = stringPreferencesKey("recents") // newline-separated, newest first
    }

    val settings: Flow<TaskbarSettings> = store.data.map { p ->
        TaskbarSettings(
            enabled = p[K.enabled] ?: false,
            edge = p[K.edge].toEnum(DockEdge.BOTTOM),
            align = p[K.align].toEnum(DockAlign.CENTER),
            iconSizeDp = p[K.iconSize] ?: 44,
            spacingDp = p[K.spacing] ?: 8,
            paddingDp = p[K.padding] ?: 10,
            cornerRadiusDp = p[K.radius] ?: 20,
            handleLengthDp = p[K.handleLen] ?: 56,
            maxVisibleIcons = p[K.maxIcons] ?: 6,
            opacityPercent = p[K.opacity] ?: 85,
            theme = p[K.theme].toEnum(ThemeMode.FOLLOW_SYSTEM),
            collapseAfterLaunch = p[K.collapseAfterLaunch] ?: true,
            autoCollapseSeconds = p[K.autoCollapse] ?: 0,
            hideOnKeyboard = p[K.hideOnKeyboard] ?: true,
            startOnBoot = p[K.startOnBoot] ?: false,
            handleOffsetFraction = p[K.handleOffset] ?: 0.5f,
            temporarilyHidden = p[K.hidden] ?: false,
        ).sanitized()
    }

    val pinned: Flow<List<String>> = store.data.map { it[K.pinned].toList() }
    val recents: Flow<List<String>> = store.data.map { it[K.recents].toList() }

    suspend fun current(): TaskbarSettings = settings.first()

    suspend fun update(transform: (TaskbarSettings) -> TaskbarSettings) {
        val next = transform(current()).sanitized()
        store.edit { p ->
            p[K.enabled] = next.enabled
            p[K.edge] = next.edge.name
            p[K.align] = next.align.name
            p[K.iconSize] = next.iconSizeDp
            p[K.spacing] = next.spacingDp
            p[K.padding] = next.paddingDp
            p[K.radius] = next.cornerRadiusDp
            p[K.handleLen] = next.handleLengthDp
            p[K.maxIcons] = next.maxVisibleIcons
            p[K.opacity] = next.opacityPercent
            p[K.theme] = next.theme.name
            p[K.collapseAfterLaunch] = next.collapseAfterLaunch
            p[K.autoCollapse] = next.autoCollapseSeconds
            p[K.hideOnKeyboard] = next.hideOnKeyboard
            p[K.startOnBoot] = next.startOnBoot
            p[K.handleOffset] = next.handleOffsetFraction
            p[K.hidden] = next.temporarilyHidden
        }
    }

    suspend fun setPinned(list: List<String>) {
        store.edit { it[K.pinned] = list.distinct().joinToString("\n") }
    }

    suspend fun pin(pkg: String) {
        val cur = pinned.first()
        if (pkg !in cur) setPinned(cur + pkg)
    }

    suspend fun unpin(pkg: String) = setPinned(pinned.first() - pkg)

    suspend fun addRecent(pkg: String, limit: Int = 12) {
        val cur = recents.first()
        if (cur.firstOrNull() == pkg) return // no duplicate consecutive entries
        store.edit { it[K.recents] = (listOf(pkg) + cur.filter { p -> p != pkg }).take(limit).joinToString("\n") }
    }

    suspend fun removeRecent(pkg: String) {
        store.edit { it[K.recents] = recents.first().filter { p -> p != pkg }.joinToString("\n") }
    }

    suspend fun clearRecents() {
        store.edit { it[K.recents] = "" }
    }

    private fun String?.toList(): List<String> =
        this?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.dataStore).also { instance = it }
            }
    }
}
