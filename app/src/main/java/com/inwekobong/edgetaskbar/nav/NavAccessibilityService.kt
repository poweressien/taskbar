package com.inwekobong.edgetaskbar.nav

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.overlay.Geometry
import com.inwekobong.edgetaskbar.overlay.dp
import com.inwekobong.edgetaskbar.overlay.dpf
import com.inwekobong.edgetaskbar.overlay.makeLabelButton
import com.inwekobong.edgetaskbar.overlay.roundedBg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Used ONLY to (1) perform Back/Home/Recents when the user taps the top bar and (2) draw that bar
 * with an accessibility overlay window. It subscribes to no events and cannot read screen content
 * (canRetrieveWindowContent=false), so it never sees what is on screen or typed.
 */
class NavAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var bar: View? = null
    private var last = TaskbarSettings()
    private lateinit var wm: WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        job = scope.launch {
            SettingsRepository.get(applicationContext).settings.collect { last = it; render() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (instance != null) render()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        job?.cancel(); job = null
        removeBar()
        if (instance === this) instance = null
    }

    private fun removeBar() {
        bar?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        bar = null
    }

    private fun render() {
        removeBar()
        if (!last.navTop || last.temporarilyHidden) return
        val dark = Geometry.isDark(this, last.theme)
        val size = dp(40)
        val a = (last.opacityPercent / 100f * 255).toInt()
        val bg = if (dark) Color.argb(a, 32, 32, 32) else Color.argb(a, 243, 243, 243)
        val stroke = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(50, 0, 0, 0)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg(bg, dpf(22f), dp(1), stroke)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            elevation = dpf(6f)
        }
        row.addView(makeLabelButton(this, "◀", size, dark, "Back") { performGlobalAction(GLOBAL_ACTION_BACK) })
        row.addView(makeLabelButton(this, "●", size, dark, "Home") { performGlobalAction(GLOBAL_ACTION_HOME) })
        row.addView(makeLabelButton(this, "■", size, dark, "Recent apps") { performGlobalAction(GLOBAL_ACTION_RECENTS) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Geometry.usableRect(this@NavAccessibilityService, wm).top + dp(2)
        }
        try { wm.addView(row, lp); bar = row } catch (_: Exception) { }
    }

    companion object {
        @Volatile var instance: NavAccessibilityService? = null
    }
}
