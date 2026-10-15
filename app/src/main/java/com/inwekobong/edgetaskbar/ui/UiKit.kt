package com.inwekobong.edgetaskbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.overlay.dp
import com.inwekobong.edgetaskbar.overlay.dpf

fun Context.text(s: String, sp: Float = 15f, bold: Boolean = false, color: Int? = null) = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    if (color != null) setTextColor(color)
}

fun Context.header(s: String) = text(s, 13f, true, 0xFF0067C0.toInt()).apply {
    setPadding(0, dp(20), 0, dp(6))
}

fun Context.button(s: String, onClick: () -> Unit) = Button(this).apply {
    text = s; isAllCaps = false; setOnClickListener { onClick() }
}

fun Context.switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
    SwitchCompat(this).apply {
        text = label
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _: CompoundButton, b: Boolean -> onChange(b) }
    }

/** Slider with a visible value; commits on release so DataStore is not written every tick. */
fun Context.sliderRow(
    label: String, min: Int, max: Int, value: Int, unit: String, onCommit: (Int) -> Unit,
): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
    val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val name = text(label).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
    val v = text("$value$unit", 14f)
    top.addView(name); top.addView(v)
    val bar = SeekBar(this).apply {
        this.max = max - min
        progress = value.coerceIn(min, max) - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { v.text = "${p + min}$unit" }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onCommit(s.progress + min) }
        })
    }
    col.addView(top); col.addView(bar)
    return col
}

/** One tile of the visual position selector: a phone outline with a bar on the chosen edge. */
class EdgeTile(ctx: Context, private val edge: DockEdge, selected: Boolean, onClick: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sel = selected

    init {
        layoutParams = LinearLayout.LayoutParams(0, ctx.dp(96), 1f).apply { setMargins(ctx.dp(4), 0, ctx.dp(4), 0) }
        contentDescription = edge.name.lowercase().replaceFirstChar { it.uppercase() }
        setOnClickListener { onClick() }
    }

    fun setSelectedTile(b: Boolean) { sel = b; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = if (sel) 0x220067C0 else 0x11888888
        c.drawRoundRect(RectF(0f, 0f, w, h), context.dpf(10f), context.dpf(10f), p)
        val pw = w * 0.45f; val ph = h * 0.78f
        val l = (w - pw) / 2; val t = (h - ph) / 2
        p.style = Paint.Style.STROKE
        p.strokeWidth = context.dpf(2f)
        p.color = if (sel) 0xFF0067C0.toInt() else 0xFF888888.toInt()
        c.drawRoundRect(RectF(l, t, l + pw, t + ph), context.dpf(8f), context.dpf(8f), p)
        p.style = Paint.Style.FILL
        p.color = if (sel) 0xFF0067C0.toInt() else Color.GRAY
        val bar = context.dpf(5f); val inset = context.dpf(8f)
        val r = when (edge) {
            DockEdge.TOP -> RectF(l + pw * 0.2f, t + inset, l + pw * 0.8f, t + inset + bar)
            DockEdge.BOTTOM -> RectF(l + pw * 0.2f, t + ph - inset - bar, l + pw * 0.8f, t + ph - inset)
            DockEdge.LEFT -> RectF(l + inset, t + ph * 0.3f, l + inset + bar, t + ph * 0.7f)
            DockEdge.RIGHT -> RectF(l + pw - inset - bar, t + ph * 0.3f, l + pw - inset, t + ph * 0.7f)
        }
        c.drawRoundRect(r, bar / 2, bar / 2, p)
    }
}

fun Context.pageColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(12), dp(20), dp(32))
    gravity = Gravity.START
}
