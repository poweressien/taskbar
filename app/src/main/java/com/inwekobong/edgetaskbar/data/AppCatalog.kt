package com.inwekobong.edgetaskbar.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers launchable apps through the launcher-intent <queries> in the manifest (no
 * QUERY_ALL_PACKAGES). The list is cached in memory; call [refresh] after package changes and
 * [load] again. Apps Android hides from us (package visibility) simply do not appear.
 */
class AppCatalog private constructor(private val context: Context) {

    @Volatile private var cache: List<AppEntry>? = null
    private val byPackage = HashMap<String, AppEntry>()

    suspend fun load(force: Boolean = false): List<AppEntry> = withContext(Dispatchers.IO) {
        val cached = cache
        if (!force && cached != null) cached else query()
    }

    /** Marks the cache stale; the next [load] re-queries PackageManager. */
    fun refresh() {
        cache = null
    }

    private fun query(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, 0)
        val list = infos.asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { ai ->
                AppEntry(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm).toString(),
                    icon = ai.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
        synchronized(byPackage) {
            byPackage.clear()
            list.forEach { byPackage[it.packageName] = it }
        }
        cache = list
        return list
    }

    /** Cached lookup; null if the app is not (or no longer) launchable or visible. */
    fun find(pkg: String): AppEntry? = synchronized(byPackage) { byPackage[pkg] }

    fun validPackages(): Set<String> = synchronized(byPackage) { byPackage.keys.toSet() }

    fun isLaunchable(pkg: String): Boolean =
        try { context.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

    fun openAppInfo(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {
            // No settings screen available: nothing sensible to do.
        }
    }

    companion object {
        @Volatile private var instance: AppCatalog? = null

        fun get(context: Context): AppCatalog =
            instance ?: synchronized(this) {
                instance ?: AppCatalog(context.applicationContext).also { instance = it }
            }
    }
}
