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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that owns the overlay. Stop is always one tap away in the notification. */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: SettingsRepository
    private lateinit var catalog: AppCatalog
    private var manager: OverlayManager? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var lastHidden: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository.get(this)
        catalog = AppCatalog.get(this)
        running = true
        ensureChannel()
        goForeground(false)

        val mgr = OverlayManager(
            appCtx = applicationContext,
            windowCtx = createWindowContextCompat(),
            scope = scope,
            repo = repo,
            catalog = catalog,
            onPermissionLost = { persistDisabledAndStop() },
        )
        manager = mgr
        mgr.start()
        registerPackageReceiver()

        scope.launch {
            // A hide from a previous run should not make a fresh start look broken.
            repo.update { it.copy(temporarilyHidden = false) }
            val apps = catalog.load(true)
            repo.seedPinnedOnce(defaultPins(apps.map { it.packageName }))
            repo.settings.combine(repo.pinned) { s, p -> s to p }
                .combine(repo.recents) { (s, p), r -> Triple(s, p, r) }
                .collect { (s, p, r) ->
                    if (!s.enabled) {
                        stopSelf()
                        return@collect
                    }
                    if (lastHidden != s.temporarilyHidden) {
                        lastHidden = s.temporarilyHidden
                        goForeground(s.temporarilyHidden)
                    }
                    mgr.update(s, p, r)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> persistDisabledAndStop()
            ACTION_TOGGLE_HIDE -> toggleHidden()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        manager?.onConfigurationChanged()
    }

    override fun onDestroy() {
        running = false
        manager?.stop()
        manager = null
        packageReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        packageReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- actions ------------------------------------------------------------------------

    /** Persists with the process-wide scope: this service's own scope is cancelled in onDestroy. */
    private fun persistDisabledAndStop() {
        val r = repo
        ioScope.launch {
            r.update { it.copy(enabled = false, temporarilyHidden = false) }
        }
        stopSelf()
    }

    private fun toggleHidden() {
        val r = repo
        ioScope.launch { r.update { it.copy(temporarilyHidden = !it.temporarilyHidden) } }
    }

    // ---- window context -----------------------------------------------------------------

    private fun createWindowContextCompat(): Context {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }
            } catch (_: Exception) {
                // fall back to the service context below
            }
        }
        return this
    }

    // ---- package changes ----------------------------------------------------------------

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch {
                    val apps = catalog.load(true)
                    // Only prune when the catalog is plausibly complete.
                    if (apps.isNotEmpty()) repo.retainPinned(catalog.validPackages())
                    manager?.refresh()
                }
            }
        }
        ContextCompat.registerReceiver(this, rec, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        packageReceiver = rec
    }

    private fun defaultPins(installed: List<String>): List<String> {
        val have = installed.toSet()
        val wanted = listOf(
            "com.whatsapp",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.contacts",
            "com.android.chrome",
            "com.google.android.gm",
            "com.google.android.apps.photos",
        )
        val picked = wanted.filter { it in have }.take(6)
        return if (picked.isNotEmpty()) picked else installed.take(5)
    }

    // ---- notification -------------------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun goForeground(hidden: Boolean) {
        val n = buildNotification(hidden)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            // Without a foreground state Android may stop us later, but the overlay can still run now.
        }
    }

    private fun buildNotification(hidden: Boolean): Notification {
        fun svc(action: String, code: Int): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(if (hidden) R.string.notif_show else R.string.notif_hide), svc(ACTION_TOGGLE_HIDE, 1))
            .addAction(0, getString(R.string.notif_stop), svc(ACTION_STOP, 2))
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.inwekobong.edgetaskbar.STOP"
        const val ACTION_TOGGLE_HIDE = "com.inwekobong.edgetaskbar.TOGGLE_HIDE"
        private const val CHANNEL = "taskbar_service"
        private const val NOTIF_ID = 1001

        @Volatile var running = false
            private set

        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }
    }
}
