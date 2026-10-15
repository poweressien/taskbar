package com.inwekobong.edgetaskbar.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers launchable apps via the manifest <queries> launcher intent (no QUERY_ALL_PACKAGES).
 * Result is cached in memory; call [refresh] after package changes or on an explicit refresh.
 */
class AppCatalog private constructor(private val context: Context) {

    @Volatile private var cache: List<AppEntry>? = null
    private val byPackage = HashMap<String, AppEntry>()

    suspend fun load(force: Boolean = false): List<AppEntry> = withContext(Dispatchers.IO) {
        if (!force) cache?.let { return@withContext it }
        refreshInternal()
    }

    fun refresh() { cache = null }

    private fun refreshInternal(): List<AppEntry> {
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

    /** Cached lookup; null if app is not (or no longer) launchable/visible. */
    fun find(pkg: String): AppEntry? = synchronized(byPackage) { byPackage[pkg] }

    /** Safe launch using the system-provided launch intent. Returns false on any failure. */
    fun launch(pkg: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openAppInfo(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) { }
    }

    fun isInstalledLaunchable(pkg: String): Boolean =
        try { context.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

    companion object {
        @Volatile private var instance: AppCatalog? = null
        fun get(context: Context): AppCatalog =
            instance ?: synchronized(this) {
                instance ?: AppCatalog(context.applicationContext).also { instance = it }
            }
    }
}
