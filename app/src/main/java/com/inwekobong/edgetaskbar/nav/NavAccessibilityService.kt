package com.inwekobong.edgetaskbar.nav

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.overlay.Geometry
import com.inwekobong.edgetaskbar.overlay.dockStyle
import com.inwekobong.edgetaskbar.overlay.dp
import com.inwekobong.edgetaskbar.overlay.iconButton
import com.inwekobong.edgetaskbar.overlay.roundedBg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Draws a small Back / Home / Recents bar at the top of the screen and presses those system
 * actions. It listens to no events and cannot read screen content (see accessibility_config.xml).
 */
class NavAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bar: LinearLayout? = null
    private var wm: WindowManager? = null
    private var latest = TaskbarSettings()

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope.launch {
            SettingsRepository.get(this@NavAccessibilityService).settings
                .map { it.navTop to it }
                .distinctUntilChanged()
                .collect { (visible, s) ->
                    latest = s
                    if (visible && !s.temporarilyHidden) show(s) else hide()
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bar != null) show(latest)
    }

    override fun onDestroy() {
        running = false
        hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun show(s: TaskbarSettings) {
        hide()
        val manager = wm ?: return
        try {
            val st = dockStyle(this, s)
            val root = LinearLayout(this)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER
            root.background = roundedBg(st.bgColor, st.radiusPx, st.strokePx, st.strokeColor)
            val pad = dp(4)
            root.setPadding(pad, pad, pad, pad)
            val items = listOf(
                Triple(R.drawable.ic_back, R.string.nav_back, GLOBAL_ACTION_BACK),
                Triple(R.drawable.ic_home, R.string.nav_home, GLOBAL_ACTION_HOME),
                Triple(R.drawable.ic_recents, R.string.nav_recents, GLOBAL_ACTION_RECENTS),
            )
            items.forEachIndexed { i, (icon, label, action) ->
                val b = iconButton(this, icon, st.ctrlPx, st, getString(label)) { performGlobalAction(action) }
                if (i > 0) (b.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                root.addView(b)
            }
            val area = Geometry.usableRect(this, manager)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = area.top + dp(4)
            lp.title = "EdgeTaskbar nav"
            manager.addView(root, lp)
            bar = root
        } catch (_: Exception) {
            bar = null
        }
    }

    private fun hide() {
        bar?.let { try { wm?.removeViewImmediate(it) } catch (_: Exception) { } }
        bar = null
    }

    companion object {
        @Volatile var running = false
            private set
    }
}
