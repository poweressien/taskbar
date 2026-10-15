package com.inwekobong.edgetaskbar.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

fun roundedBg(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun rippleOf(radiusPx: Float, dark: Boolean): RippleDrawable {
    val mask = GradientDrawable().apply { cornerRadius = radiusPx; setColor(Color.WHITE) }
    val tint = if (dark) 0x44FFFFFF else 0x33000000
    return RippleDrawable(ColorStateList.valueOf(tint), ColorDrawable(Color.TRANSPARENT), mask)
}

/**
 * Collapsed handle: a small touch target with a pill drawn inside. Distinguishes tap from drag;
 * dragging is constrained to the current edge by the callbacks.
 */
class HandleView(
    ctx: Context,
    private val horizontalEdge: Boolean,
    pillColor: Int,
    strokeColor: Int,
    private val onTap: () -> Unit,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : FrameLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        val pill = View(ctx)
        pill.background = roundedBg(pillColor, ctx.dpf(3f), ctx.dp(1), strokeColor)
        val thin = ctx.dp(5)
        val lp = if (horizontalEdge) LayoutParams(ctx.dp(36), thin) else LayoutParams(thin, ctx.dp(36))
        lp.gravity = Gravity.CENTER
        addView(pill, lp)
        contentDescription = "Open taskbar"
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                if (dragging) onDrag(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onDragEnd() else { performClick(); onTap() }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/**
 * Container that turns a swipe along its main axis into a page change, intercepting before
 * children see the gesture so a swipe never triggers an icon launch.
 */
class SwipeLayout(
    ctx: Context,
    private val horizontal: Boolean,
    private val onSwipe: (forward: Boolean) -> Unit,
) : LinearLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val threshold = ctx.dp(24)
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    init { orientation = if (horizontal) HORIZONTAL else VERTICAL }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val main = if (horizontal) e.x - downX else e.y - downY
                val cross = if (horizontal) e.y - downY else e.x - downX
                if (abs(main) > slop && abs(main) > abs(cross)) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> return swiping
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    val main = if (horizontal) e.x - downX else e.y - downY
                    if (abs(main) >= threshold) onSwipe(main < 0) // swipe toward start => next page
                    swiping = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { swiping = false }
        }
        return swiping
    }
}

/** Root of the expanded dock window; reports touches so the auto-collapse timer can reset. */
class DockRoot(ctx: Context, private val onActivity: () -> Unit, private val onOutside: () -> Unit) :
    LinearLayout(ctx) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(); return true }
        onActivity()
        return super.dispatchTouchEvent(ev)
    }
}

fun makeLabelButton(ctx: Context, text: String, sizePx: Int, dark: Boolean, desc: String, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (dark) Color.WHITE else Color.BLACK)
        contentDescription = desc
        background = rippleOf(sizePx / 2f, dark)
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        setOnClickListener { onClick() }
    }

fun makeIcon(
    ctx: Context, drawable: android.graphics.drawable.Drawable, sizePx: Int, dark: Boolean,
    label: String, onClick: () -> Unit, onLongClick: () -> Unit,
): ImageView = ImageView(ctx).apply {
    setImageDrawable(drawable)
    scaleType = ImageView.ScaleType.FIT_CENTER
    contentDescription = label
    val pad = sizePx / 10
    setPadding(pad, pad, pad, pad)
    background = rippleOf(ctx.dpf(10f), dark)
    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
    setOnClickListener { onClick() }
    setOnLongClickListener { onLongClick(); true }
}

fun dot(ctx: Context, active: Boolean, dark: Boolean): View = View(ctx).apply {
    val color = if (dark) Color.WHITE else Color.BLACK
    background = roundedBg(if (active) color else (color and 0x00FFFFFF) or 0x55000000, ctx.dpf(2f))
    layoutParams = LinearLayout.LayoutParams(ctx.dp(if (active) 12 else 4), ctx.dp(4)).apply {
        setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
    }
}

fun dotVertical(ctx: Context, active: Boolean, dark: Boolean): View = View(ctx).apply {
    val color = if (dark) Color.WHITE else Color.BLACK
    background = roundedBg(if (active) color else (color and 0x00FFFFFF) or 0x55000000, ctx.dpf(2f))
    layoutParams = LinearLayout.LayoutParams(ctx.dp(4), ctx.dp(if (active) 12 else 4)).apply {
        setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
    }
}

fun ViewGroup.addAll(vararg v: View) = v.forEach { addView(it) }
