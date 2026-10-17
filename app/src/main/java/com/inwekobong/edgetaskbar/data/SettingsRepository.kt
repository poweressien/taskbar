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

private val Context.edgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "edge_taskbar")

/** Single source of truth for settings, pinned apps and recents. All data stays on the device. */
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
        val pinnedBar = booleanPreferencesKey("pinned_bar")
        val navTop = booleanPreferencesKey("nav_top")
        val windowMode = stringPreferencesKey("window_mode")
        val pinned = stringPreferencesKey("pinned") // newline-separated package names, in order
        val recents = stringPreferencesKey("recents") // newline-separated, newest first
        val seeded = booleanPreferencesKey("seeded")
    }

    val settings: Flow<TaskbarSettings> = store.data.map { read(it) }

    val pinned: Flow<List<String>> = store.data.map { ListOps.decode(it[K.pinned]) }
    val recents: Flow<List<String>> = store.data.map { ListOps.decode(it[K.recents]) }

    suspend fun current(): TaskbarSettings = settings.first()

    /** Atomically reads, transforms, clamps and saves the settings. */
    suspend fun update(transform: (TaskbarSettings) -> TaskbarSettings) {
        store.edit { p ->
            val next = transform(read(p)).sanitized()
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
            p[K.pinnedBar] = next.pinnedBar
            p[K.navTop] = next.navTop
            p[K.windowMode] = next.windowMode.name
        }
    }

    private fun read(p: Preferences): TaskbarSettings = TaskbarSettings(
        enabled = p[K.enabled] ?: false,
        edge = enumOrDefault(p[K.edge], DockEdge.BOTTOM),
        align = enumOrDefault(p[K.align], DockAlign.CENTER),
        iconSizeDp = p[K.iconSize] ?: 44,
        spacingDp = p[K.spacing] ?: 8,
        paddingDp = p[K.padding] ?: 10,
        cornerRadiusDp = p[K.radius] ?: 20,
        handleLengthDp = p[K.handleLen] ?: 56,
        maxVisibleIcons = p[K.maxIcons] ?: 6,
        opacityPercent = p[K.opacity] ?: 85,
        theme = enumOrDefault(p[K.theme], ThemeMode.FOLLOW_SYSTEM),
        collapseAfterLaunch = p[K.collapseAfterLaunch] ?: true,
        autoCollapseSeconds = p[K.autoCollapse] ?: 0,
        hideOnKeyboard = p[K.hideOnKeyboard] ?: true,
        startOnBoot = p[K.startOnBoot] ?: false,
        handleOffsetFraction = p[K.handleOffset] ?: 0.5f,
        temporarilyHidden = p[K.hidden] ?: false,
        pinnedBar = p[K.pinnedBar] ?: true,
        navTop = p[K.navTop] ?: false,
        windowMode = enumOrDefault(p[K.windowMode], WindowMode.FULL),
    ).sanitized()

    // ---- pinned apps --------------------------------------------------------------------

    suspend fun setPinned(list: List<String>) {
        store.edit { it[K.pinned] = ListOps.encode(list) }
    }

    suspend fun pin(pkg: String) {
        store.edit { p -> p[K.pinned] = ListOps.encode(ListOps.pin(ListOps.decode(p[K.pinned]), pkg)) }
    }

    suspend fun pinAt(index: Int, pkg: String) {
        store.edit { p -> p[K.pinned] = ListOps.encode(ListOps.insert(ListOps.decode(p[K.pinned]), index, pkg)) }
    }

    suspend fun unpin(pkg: String) {
        store.edit { p -> p[K.pinned] = ListOps.encode(ListOps.unpin(ListOps.decode(p[K.pinned]), pkg)) }
    }

    /** Removes pins whose package is not in [valid]. Returns how many were removed. */
    suspend fun retainPinned(valid: Set<String>): Int {
        var removed = 0
        store.edit { p ->
            val cur = ListOps.decode(p[K.pinned])
            val kept = ListOps.keepValid(cur, valid)
            removed = cur.size - kept.size
            if (removed > 0) p[K.pinned] = ListOps.encode(kept)
        }
        return removed
    }

    /** First run only: pins [candidates] if nothing is pinned yet so the bar is not empty. */
    suspend fun seedPinnedOnce(candidates: List<String>) {
        store.edit { p ->
            if (p[K.seeded] != true) {
                p[K.seeded] = true
                if (ListOps.decode(p[K.pinned]).isEmpty()) p[K.pinned] = ListOps.encode(candidates)
            }
        }
    }

    // ---- recents ------------------------------------------------------------------------

    suspend fun addRecent(pkg: String) {
        store.edit { p -> p[K.recents] = ListOps.encode(ListOps.addRecent(ListOps.decode(p[K.recents]), pkg)) }
    }

    suspend fun removeRecent(pkg: String) {
        store.edit { p -> p[K.recents] = ListOps.encode(ListOps.unpin(ListOps.decode(p[K.recents]), pkg)) }
    }

    suspend fun clearRecents() {
        store.edit { it[K.recents] = "" }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.edgeDataStore).also { instance = it }
            }
    }
}
