package com.inwekobong.edgetaskbar.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.data.ThemeMode
import com.inwekobong.edgetaskbar.windows.IntRect
import kotlin.math.max

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

object Geometry {

    /**
     * Usable screen rectangle in screen coordinates: excludes system bars and display cutouts and,
     * on the left/right, the system gesture zones. Recomputed whenever it is needed, so rotation,
     * folding and multi-window size changes are picked up. Nothing is hardcoded per phone.
     */
    @Suppress("DEPRECATION")
    fun usableRect(ctx: Context, wm: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val m = wm.currentWindowMetrics
                val b = m.bounds
                val bars = m.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val gest = m.windowInsets.getInsets(WindowInsets.Type.systemGestures())
                return Rect(
                    b.left + max(bars.left, gest.left),
                    b.top + bars.top,
                    b.right - max(bars.right, gest.right),
                    b.bottom - bars.bottom
                )
            } catch (_: Exception) {
                // fall through to the metrics-based estimate below
            }
        }
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val res = ctx.resources
        fun dimen(name: String): Int {
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }
        val landscape = dm.widthPixels > dm.heightPixels
        val nav = dimen("navigation_bar_height")
        return Rect(
            0,
            dimen("status_bar_height"),
            dm.widthPixels - if (landscape) nav else 0,
            dm.heightPixels - if (landscape) 0 else nav
        )
    }

    /** Area new windows may occupy: usable screen minus the pinned bottom bar and top nav bar. */
    fun windowArea(ctx: Context, wm: WindowManager, s: TaskbarSettings): IntRect {
        val r = usableRect(ctx, wm)
        val top = r.top + if (s.navTop) ctx.dp(44) else 0
        val bottom = r.bottom - if (s.pinnedBar) ctx.dp(s.iconSizeDp + 2 * s.paddingDp + 16) else 0
        return IntRect(r.left, top, r.right, max(top + 1, bottom))
    }

    fun isDark(ctx: Context, mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM ->
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
