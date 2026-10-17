package com.inwekobong.edgetaskbar.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Targeting Android 15+ draws apps edge to edge, so content would sit under the status bar and
 * the navigation bar. This adds the bar, cutout and keyboard sizes to the view's own padding.
 */
fun View.applySystemBarPadding(extraTop: Int = 0, extraBottom: Int = 0) {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(
            l + bars.left,
            t + bars.top + extraTop,
            r + bars.right,
            b + maxOf(bars.bottom, ime.bottom) + extraBottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
