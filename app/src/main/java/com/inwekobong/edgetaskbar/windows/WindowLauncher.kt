package com.inwekobong.edgetaskbar.windows

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.data.WindowMode
import com.inwekobong.edgetaskbar.overlay.Geometry
import com.inwekobong.edgetaskbar.overlay.dp

/**
 * Launches apps into window slots using only public APIs (launch bounds, adjacent-launch flag).
 * The system decides whether to honour them: free-form/resizable windows need the device's
 * multi-window support (and, on most phones, developer options). Apps that opt out of resizing
 * (resizeableActivity=false) will still open full screen. Never throws.
 */
object WindowLauncher {
    private var next = 0

    fun resetSlots() { next = 0 }

    fun launch(ctx: Context, s: TaskbarSettings, pkg: String): Boolean {
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (s.windowMode == WindowMode.FULL) {
                ctx.startActivity(intent)
                return true
            }
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val area = Geometry.windowArea(ctx, wm, s)
            val slots = LayoutMath.slots(s.windowMode, area, ctx.dp(4))
            val r = slots[next % slots.size]
            next++
            val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(r.left, r.top, r.right, r.bottom))
            if (s.windowMode == WindowMode.FREEFORM) forceFreeform(opts)
            if (s.windowMode == WindowMode.SPLIT) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            ctx.startActivity(intent, opts.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Hidden API on many builds; silently ignored if unavailable. */
    private fun forceFreeform(o: ActivityOptions) {
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(o, 5) // WINDOWING_MODE_FREEFORM
        } catch (_: Throwable) { }
    }
}
