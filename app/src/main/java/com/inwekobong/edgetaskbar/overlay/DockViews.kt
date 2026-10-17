package com.inwekobong.edgetaskbar.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import com.inwekobong.edgetaskbar.data.AppEntry
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/** Every size and colour the overlay needs, derived once from the settings. */
class DockStyle(
    val dark: Boolean,
    val iconPx: Int,
    val gapPx: Int,
    val padPx: Int,
    val ctrlPx: Int,
    val radiusPx: Float,
    val strokePx: Int,
    val bgColor: Int,
    val strokeColor: Int,
    val fgColor: Int,
)

fun dockStyle(ctx: Context, s: TaskbarSettings): DockStyle {
    val dark = Geometry.isDark(ctx, s.theme)
    val alpha = (s.opacityPercent / 100f * 255).roundToInt().coerceIn(0, 255)
    return DockStyle(
        dark = dark,
        iconPx = ctx.dp(s.iconSizeDp),
        gapPx = ctx.dp(s.spacingDp),
        padPx = ctx.dp(s.paddingDp),
        ctrlPx = ctx.dp(36),
        radiusPx = ctx.dpf(s.cornerRadiusDp.toFloat()),
        strokePx = ctx.dp(1),
        bgColor = if (dark) Color.argb(alpha, 32, 32, 32) else Color.argb(alpha, 243, 243, 243),
        strokeColor = if (dark) Color.argb(70, 255, 255, 255) else Color.argb(60, 0, 0, 0),
        fgColor = if (dark) Color.WHITE else Color.rgb(27, 27, 27),
    )
}

fun roundedBg(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
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

/** Gives each icon its own Drawable instance; sharing one between views corrupts its bounds. */
private fun freshCopy(ctx: Context, d: Drawable): Drawable =
    d.constantState?.newDrawable(ctx.resources)?.mutate() ?: d

fun iconButton(
    ctx: Context, @DrawableRes res: Int, sizePx: Int, st: DockStyle, desc: String, onClick: () -> Unit,
): ImageView = ImageView(ctx).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(st.fgColor)
    scaleType = ImageView.ScaleType.FIT_CENTER
    val pad = sizePx / 4
    setPadding(pad, pad, pad, pad)
    contentDescription = desc
    background = rippleOf(ctx.dpf(10f), st.dark)
    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
    setOnClickListener { onClick() }
}

fun appIconView(
    ctx: Context, app: AppEntry, sizePx: Int, st: DockStyle, onClick: () -> Unit, onLongClick: () -> Unit,
): ImageView = ImageView(ctx).apply {
    setImageDrawable(freshCopy(ctx, app.icon))
    scaleType = ImageView.ScaleType.FIT_CENTER
    val pad = sizePx / 10
    setPadding(pad, pad, pad, pad)
    contentDescription = app.label
    background = rippleOf(ctx.dpf(10f), st.dark)
    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
    setOnClickListener { onClick() }
    setOnLongClickListener { onLongClick(); true }
}

fun pageDot(ctx: Context, active: Boolean, st: DockStyle, horizontal: Boolean): View = View(ctx).apply {
    val base = st.fgColor
    val color = if (active) base else (base and 0x00FFFFFF) or 0x55000000
    background = roundedBg(color, ctx.dpf(2f))
    val long = ctx.dp(if (active) 12 else 4)
    val short = ctx.dp(4)
    layoutParams = LinearLayout.LayoutParams(if (horizontal) long else short, if (horizontal) short else long).apply {
        setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
    }
}

fun divider(ctx: Context, st: DockStyle, horizontalStrip: Boolean): View = View(ctx).apply {
    background = ColorDrawable((st.fgColor and 0x00FFFFFF) or 0x33000000)
    val thin = ctx.dp(1)
    val long = ctx.dp(24)
    layoutParams = LinearLayout.LayoutParams(if (horizontalStrip) thin else long, if (horizontalStrip) long else thin)
}

/** Adds [gap] before this view along the strip's main axis. */
fun View.gapBefore(horizontalStrip: Boolean, gap: Int) {
    val lp = layoutParams as? LinearLayout.LayoutParams ?: return
    if (horizontalStrip) lp.marginStart = gap else lp.topMargin = gap
}

/**
 * The collapsed handle: a small tab with a pill glyph. Taps expand the dock, drags slide it along
 * its edge. The window is only as big as this view, so everything else stays touchable.
 */
class HandleView(
    ctx: Context,
    horizontalEdge: Boolean,
    st: DockStyle,
    private val onTap: () -> Unit,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : FrameLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        background = roundedBg(st.bgColor, ctx.dpf(12f), st.strokePx, st.strokeColor)
        val pill = View(ctx)
        pill.background = roundedBg(st.fgColor, ctx.dpf(3f))
        val thin = ctx.dp(4)
        val long = ctx.dp(28)
        val lp = LayoutParams(if (horizontalEdge) long else thin, if (horizontalEdge) thin else long)
        lp.gravity = Gravity.CENTER
        addView(pill, lp)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Turns a swipe along its main axis into a page change. It intercepts before the icons see the
 * gesture, so finishing a swipe never launches an app.
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

    init {
        orientation = if (horizontal) HORIZONTAL else VERTICAL
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                swiping = false
            }
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
                    if (abs(main) >= threshold) onSwipe(main < 0) // swipe toward the start = next page
                    swiping = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return swiping
    }
}

/** Root of the expanded dock window. Reports activity (for the auto-collapse timer) and outside taps. */
class DockRoot(
    ctx: Context,
    private val onActivity: () -> Unit,
    private val onOutside: () -> Unit,
) : LinearLayout(ctx) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        onActivity()
        return super.dispatchTouchEvent(ev)
    }
}
