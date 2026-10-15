package com.inwekobong.edgetaskbar.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the overlay. A foreground service is justified because the dock is
 * an ongoing, user-visible feature; the notification offers Stop and Hide/Show actions.
 * Start/stop are idempotent and the service stops itself if overlay permission is lost.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: SettingsRepository
    private lateinit var catalog: AppCatalog
    private var manager: OverlayManager? = null
    private var collectJob: Job? = null
    private var running = false
    private var hidden = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            catalog.refresh()
            scope.launch {
                catalog.load(true)
                // Drop pins whose app was uninstalled.
                val current = repo.pinned.first()
                val alive = current.filter { catalog.isInstalledLaunchable(it) }
                if (alive.size != current.size) repo.setPinned(alive)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository.get(this)
        catalog = AppCatalog.get(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(disable = true); return START_NOT_STICKY }
            ACTION_TOGGLE_HIDE -> {
                scope.launch { repo.update { it.copy(temporarilyHidden = !it.temporarilyHidden) } }
                if (!running) return START_NOT_STICKY
                return START_STICKY
            }
        }

        if (!enterForeground()) { stopSelf(); return START_NOT_STICKY }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            shutdown(disable = true)
            return START_NOT_STICKY
        }
        if (running) return START_STICKY // idempotent: never add a second overlay
        running = true

        scope.launch { repo.update { it.copy(enabled = true) } }
        val m = OverlayManager(this, scope, repo, catalog) { shutdown(disable = true) }
        manager = m
        m.start()

        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        collectJob = scope.launch {
            catalog.load()
            combine(repo.settings, repo.pinned, repo.recents) { s, p, r -> Triple(s, p, r) }
                .collect { (s, p, r) ->
                    if (s.temporarilyHidden != hidden) { hidden = s.temporarilyHidden; notifyUpdate() }
                    m.update(s, p, r)
                }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        manager?.onConfigurationChanged()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun teardown() {
        collectJob?.cancel(); collectJob = null
        manager?.stop(); manager = null
        if (running) {
            try { unregisterReceiver(packageReceiver) } catch (_: Exception) { }
        }
        running = false
    }

    private fun shutdown(disable: Boolean) {
        teardown()
        if (disable) scope.launch { repo.update { it.copy(enabled = false) } }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when restarted in a restricted state.
        false
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags)
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_HIDE), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_stop), stop)
            .addAction(0, if (hidden) "Show" else getString(R.string.notif_hide), toggle)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.inwekobong.edgetaskbar.STOP"
        const val ACTION_TOGGLE_HIDE = "com.inwekobong.edgetaskbar.TOGGLE_HIDE"
        private const val CHANNEL = "taskbar"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
