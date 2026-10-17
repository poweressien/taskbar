package com.inwekobong.edgetaskbar.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.overlay.OverlayService
import com.inwekobong.edgetaskbar.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts the taskbar after a reboot, only if the user turned that on. Some phone makers block
 * this; if the start is refused we post a tap-to-start notification instead of failing silently.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = SettingsRepository.get(app).current()
                if (s.enabled && s.startOnBoot && Settings.canDrawOverlays(app)) {
                    try {
                        OverlayService.start(app)
                    } catch (_: Exception) {
                        notifyBlocked(app)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyBlocked(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("boot", ctx.getString(R.string.boot_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, "boot")
            .setSmallIcon(R.drawable.ic_stat_dock)
            .setContentTitle(ctx.getString(R.string.boot_blocked_title))
            .setContentText(ctx.getString(R.string.boot_blocked_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(2002, n) } catch (_: SecurityException) { }
    }
}
