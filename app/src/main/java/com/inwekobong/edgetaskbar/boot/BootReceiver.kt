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
import kotlinx.coroutines.launch

/**
 * Starts the taskbar after reboot only if the user opted in AND overlay permission is still granted.
 * If Android or an OEM blocks the start, a notification lets the user start it manually.
 * Not available before first unlock (credential-protected storage), and some OEMs block auto-start.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = SettingsRepository.get(context).current()
                if (!s.startOnBoot) return@launch
                val canDraw = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
                if (!canDraw) { notifyBlocked(context); return@launch }
                try {
                    OverlayService.start(context)
                } catch (e: Exception) {
                    notifyBlocked(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyBlocked(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("boot", "Startup", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, "boot")
            .setSmallIcon(R.drawable.ic_dock)
            .setContentTitle(ctx.getString(R.string.boot_blocked_title))
            .setContentText(ctx.getString(R.string.boot_blocked_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(2002, n) } catch (_: SecurityException) { }
    }
}
