package com.inwekobong.edgetaskbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.inwekobong.edgetaskbar.data.DockAlign
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.overlay.Geometry
import kotlin.math.min

/** A small phone drawing that shows the taskbar the way the current settings would place it. */
class DockPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private var s = TaskbarSettings().effective()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun render(settings: TaskbarSettings) {
        s = settings.effective()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val dark = Geometry.isDark(context, s.theme)
        val phoneH = height - dpf(16f)
        val phoneW = min(width - dpf(16f), phoneH * 0.52f)
        val left = (width - phoneW) / 2f
        val top = dpf(8f)

        // Phone body and screen.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(40, 44, 52)
        rect.set(left, top, left + phoneW, top + phoneH)
        c.drawRoundRect(rect, dpf(18f), dpf(18f), paint)
        val inset = dpf(5f)
        paint.shader = android.graphics.LinearGradient(
            0f, top, 0f, top + phoneH, Color.rgb(47, 134, 240), Color.rgb(10, 58, 140),
            android.graphics.Shader.TileMode.CLAMP
        )
        rect.set(left + inset, top + inset, left + phoneW - inset, top + phoneH - inset)
        c.drawRoundRect(rect, dpf(14f), dpf(14f), paint)
        paint.shader = null
        val screen = RectF(rect)

        // Scale the real dock sizes down to the drawing.
        val scale = screen.width() / 360f
        val icon = s.iconSizeDp * scale
        val gap = s.spacingDp * scale
        val pad = s.paddingDp * scale
        val count = min(s.maxVisibleIcons, 5)
        val main = count * icon + (count - 1) * gap + 2 * pad
        val cross = icon + 2 * pad
        val margin = 6f * scale

        val w: Float
        val h: Float
        if (s.edge.isHorizontal) {
            w = if (s.pinnedBar) screen.width() - 2 * margin else min(main, screen.width() - 2 * margin)
            h = cross
        } else {
            w = cross
            h = min(main, screen.height() - 2 * margin)
        }
        val x: Float
        val y: Float
        when (s.edge) {
            DockEdge.TOP, DockEdge.BOTTOM -> {
                x = when (s.align) {
                    DockAlign.START -> screen.left + margin
                    DockAlign.CENTER -> screen.left + (screen.width() - w) / 2f
                    DockAlign.END -> screen.right - w - margin
                }
                y = if (s.edge == DockEdge.TOP) screen.top + margin else screen.bottom - h - margin
            }
            DockEdge.LEFT, DockEdge.RIGHT -> {
                x = if (s.edge == DockEdge.LEFT) screen.left + margin else screen.right - w - margin
                y = screen.top + (screen.height() - h) * s.handleOffsetFraction
            }
        }

        val alpha = (s.opacityPercent / 100f * 255).toInt().coerceIn(0, 255)
        paint.color = if (dark) Color.argb(alpha, 32, 32, 32) else Color.argb(alpha, 243, 243, 243)
        rect.set(x, y, x + w, y + h)
        val radius = min(s.cornerRadiusDp * scale, min(w, h) / 2f)
        c.drawRoundRect(rect, radius, radius, paint)

        // Icons: coloured squares standing in for apps.
        val colors = intArrayOf(
            Color.rgb(0, 120, 212), Color.rgb(0, 204, 106), Color.rgb(255, 185, 0),
            Color.rgb(247, 99, 12), Color.rgb(142, 140, 216),
        )
        val used = if (s.edge.isHorizontal) w else h
        val total = count * icon + (count - 1) * gap
        var cursor = (used - total) / 2f
        for (i in 0 until count) {
            paint.color = colors[i % colors.size]
            if (s.edge.isHorizontal) rect.set(x + cursor, y + pad, x + cursor + icon, y + pad + icon)
            else rect.set(x + pad, y + cursor, x + pad + icon, y + cursor + icon)
            c.drawRoundRect(rect, icon * 0.25f, icon * 0.25f, paint)
            cursor += icon + gap
        }

        // Top navigation bar, if enabled.
        if (s.navTop) {
            val bw = 54f * scale
            val bh = 18f * scale
            paint.color = if (dark) Color.argb(alpha, 32, 32, 32) else Color.argb(alpha, 243, 243, 243)
            rect.set(screen.centerX() - bw / 2, screen.top + 6f * scale, screen.centerX() + bw / 2, screen.top + 6f * scale + bh)
            c.drawRoundRect(rect, bh / 2, bh / 2, paint)
        }
    }
}
