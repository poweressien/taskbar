package com.inwekobong.edgetaskbar.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.inwekobong.edgetaskbar.data.ThemeMode
import kotlin.math.max

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

object Geometry {

    /**
     * Usable screen rectangle in screen coordinates, excluding system bars, display cutouts and
     * (horizontally) system gesture zones, so the dock never sits where it cannot be tapped.
     * Recomputed on every layout pass; nothing is hardcoded per device.
     */
    @Suppress("DEPRECATION")
    fun usableRect(ctx: Context, wm: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val b = m.bounds
            val ins = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val g = m.windowInsets.getInsets(WindowInsets.Type.systemGestures())
            return Rect(
                b.left + max(ins.left, g.left),
                b.top + ins.top,
                b.right - max(ins.right, g.right),
                b.bottom - ins.bottom
            )
        }
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val res = ctx.resources
        fun dimen(name: String): Int {
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }
        val status = dimen("status_bar_height")
        val nav = dimen("navigation_bar_height")
        val landscape = dm.widthPixels > dm.heightPixels
        return Rect(
            0, status,
            dm.widthPixels - if (landscape) nav else 0,
            dm.heightPixels - if (landscape) 0 else nav
        )
    }

    fun isDark(ctx: Context, mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM ->
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
