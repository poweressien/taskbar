package com.inwekobong.edgetaskbar.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.DockAlign
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.data.WindowMode
import com.inwekobong.edgetaskbar.windows.WindowLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns every WindowManager view. Uses at most three small windows:
 *  - a handle (collapsed state),
 *  - the dock (expanded state, ACTION_OUTSIDE collapses it),
 *  - a 1px non-touchable keyboard probe (best-effort IME detection).
 * None is screen-sized and touchable, so everything outside the dock stays usable.
 */
class OverlayManager(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val catalog: AppCatalog,
    private val onPermissionLost: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var settings = TaskbarSettings()
    private var pinned: List<String> = emptyList()
    private var recents: List<String> = emptyList()

    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var dockView: View? = null
    private var probeView: View? = null

    private var expanded = false
    private var page = 0
    private var imeVisible = false
    private var started = false
    private var dragStartX = 0
    private var dragStartY = 0

    private val collapseRunnable = Runnable { collapse() }
    private val imeRunnable = Runnable { applyState() }

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ---- public API ----------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        addProbe()
    }

    fun update(s: TaskbarSettings, pins: List<String>, recent: List<String>) {
        settings = s
        pinned = pins
        recents = recent
        applyState()
    }

    /** Called on rotation, display-size, or multi-window changes. */
    fun onConfigurationChanged() = applyState()

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        removeHandle(); removeDock(); removeProbe()
        expanded = false
    }

    fun collapse() {
        if (!expanded || settings.pinnedBar) return
        expanded = false
        handler.removeCallbacks(collapseRunnable)
        applyState()
    }

    fun expand() {
        if (expanded) return
        expanded = true
        page = 0
        applyState()
    }

    // ---- state machine -------------------------------------------------------------------

    private fun applyState() {
        if (!started) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            stop(); onPermissionLost(); return
        }
        if (settings.pinnedBar) expanded = true // pinned bar never collapses
        val suppressed = settings.temporarilyHidden || (settings.hideOnKeyboard && imeVisible)
        if (suppressed) { removeHandle(); removeDock(); return }
        try {
            if (expanded) { removeHandle(); showDock() } else { removeDock(); showHandle() }
        } catch (e: WindowManager.BadTokenException) {
            stop(); onPermissionLost()
        } catch (e: SecurityException) {
            stop(); onPermissionLost()
        }
    }

    private fun baseParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, overlayType,
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // ---- handle --------------------------------------------------------------------------

    private fun showHandle() {
        removeHandle()
        val s = settings
        val dark = Geometry.isDark(ctx, s.theme)
        val horizontal = s.edge.isHorizontal
        val thick = ctx.dp(24)
        val length = ctx.dp(s.handleLengthDp)
        val w = if (horizontal) length else thick
        val h = if (horizontal) thick else length
        val r = Geometry.usableRect(ctx, wm)

        val pill = if (dark) Color.argb(230, 235, 235, 235) else Color.argb(230, 40, 40, 40)
        val stroke = if (dark) Color.argb(120, 0, 0, 0) else Color.argb(120, 255, 255, 255)

        val lp = baseParams(w, h, 0)
        placeHandle(lp, r, w, h, s.handleOffsetFraction)

        val v = HandleView(
            ctx, horizontal, pill, stroke,
            onTap = { expand() },
            onDrag = { dx, dy -> dragHandle(lp, r, w, h, dx, dy) },
            onDragEnd = { snapHandle(lp, r, w, h) },
        )
        dragStartX = lp.x; dragStartY = lp.y
        wm.addView(v, lp)
        handleView = v
        handleParams = lp
    }

    private fun placeHandle(lp: WindowManager.LayoutParams, r: Rect, w: Int, h: Int, frac: Float) {
        val e = settings.edge
        if (e.isHorizontal) {
            val range = max(0, r.width() - w)
            lp.x = r.left + (range * frac).roundToInt()
            lp.y = if (e == DockEdge.TOP) r.top else r.bottom - h
        } else {
            val range = max(0, r.height() - h)
            lp.y = r.top + (range * frac).roundToInt()
            lp.x = if (e == DockEdge.LEFT) r.left else r.right - w
        }
    }

    private fun dragHandle(lp: WindowManager.LayoutParams, r: Rect, w: Int, h: Int, dx: Float, dy: Float) {
        val v = handleView ?: return
        if (settings.edge.isHorizontal) {
            lp.x = (dragStartX + dx.roundToInt()).coerceIn(r.left, max(r.left, r.right - w))
        } else {
            lp.y = (dragStartY + dy.roundToInt()).coerceIn(r.top, max(r.top, r.bottom - h))
        }
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private fun snapHandle(lp: WindowManager.LayoutParams, r: Rect, w: Int, h: Int) {
        val frac = if (settings.edge.isHorizontal) {
            val range = max(1, r.width() - w)
            (lp.x - r.left).toFloat() / range
        } else {
            val range = max(1, r.height() - h)
            (lp.y - r.top).toFloat() / range
        }.coerceIn(0f, 1f)
        // Snap to nearest of 0, 0.25, 0.5, 0.75, 1 so the handle lands on a tidy position.
        val snapped = (frac * 4f).roundToInt() / 4f
        placeHandle(lp, r, w, h, snapped)
        handleView?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) { } }
        dragStartX = lp.x; dragStartY = lp.y
        scope.launch { repo.update { it.copy(handleOffsetFraction = snapped) } }
    }

    private fun removeHandle() {
        handleView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        handleView = null; handleParams = null
    }

    // ---- dock ----------------------------------------------------------------------------

    private fun showDock() {
        removeDock()
        val s = settings.let {
            if (it.pinnedBar) it.copy(edge = DockEdge.BOTTOM, align = DockAlign.CENTER) else it
        }
        val dark = Geometry.isDark(ctx, s.theme)
        val horizontal = s.edge.isHorizontal
        val r = Geometry.usableRect(ctx, wm)
        val margin = ctx.dp(8)

        val iconPx = ctx.dp(s.iconSizeDp)
        val gapPx = ctx.dp(s.spacingDp)
        val padPx = ctx.dp(s.paddingDp)
        val ctrlPx = ctx.dp(32)

        val entries = pinned.mapNotNull { catalog.find(it) }
        val recentEntries = recents.mapNotNull { catalog.find(it) }
            .take(s.maxVisibleIcons)

        // How many icons fit on a page along the dock's main axis.
        val avail = (if (horizontal) r.width() else r.height()) - 2 * margin - 2 * padPx - 3 * ctrlPx
        val fit = max(1, avail / (iconPx + gapPx))
        val pageSize = min(s.maxVisibleIcons, fit)
        val pages = max(1, (entries.size + pageSize - 1) / pageSize)
        page = page.coerceIn(0, pages - 1)

        val bgAlpha = (s.opacityPercent / 100f * 255).roundToInt()
        val bgColor = if (dark) Color.argb(bgAlpha, 32, 32, 32) else Color.argb(bgAlpha, 243, 243, 243)
        val strokeColor = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(50, 0, 0, 0)

        val root = DockRoot(ctx, onActivity = { scheduleAutoCollapse() }, onOutside = { collapse() }).apply {
            orientation = if (horizontal) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            background = roundedBg(bgColor, ctx.dpf(s.cornerRadiusDp.toFloat()), ctx.dp(1), strokeColor)
            setPadding(padPx, padPx, padPx, padPx)
            elevation = ctx.dpf(8f)
            clipToPadding = false
            if (s.pinnedBar) gravity = Gravity.CENTER_HORIZONTAL
        }

        // Primary strip: [icons (swipeable)] [collapse] [hide]
        val strip = LinearLayout(ctx).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val swipe = SwipeLayout(ctx, horizontal) { forward ->
            val next = (page + if (forward) 1 else -1).coerceIn(0, pages - 1)
            if (next != page) { page = next; showDock() }
        }.apply { gravity = Gravity.CENTER }

        val from = page * pageSize
        val slice = entries.drop(from).take(pageSize)
        if (entries.isEmpty()) {
            swipe.addView(makeLabelButton(ctx, "+", iconPx, dark, "No pinned apps") { openMain() })
        }
        slice.forEachIndexed { i, app ->
            val icon = makeIcon(ctx, app.icon, iconPx, dark, app.label,
                onClick = { launchApp(app.packageName) },
                onLongClick = { catalog.openAppInfo(app.packageName); collapse() })
            (icon.layoutParams as LinearLayout.LayoutParams).apply {
                if (i > 0) { if (horizontal) leftMargin = gapPx else topMargin = gapPx }
            }
            swipe.addView(icon)
        }
        strip.addView(swipe)
        strip.addView(makeLabelButton(ctx, s.windowMode.glyph, ctrlPx, dark,
            "Window layout: ${s.windowMode.label}. Tap to change") { cycleLayout() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).apply {
                if (horizontal) leftMargin = gapPx else topMargin = gapPx
            }
        })
        if (!s.pinnedBar) {
            strip.addView(makeLabelButton(ctx, "–", ctrlPx, dark, "Collapse dock") { collapse() })
        }
        strip.addView(makeLabelButton(ctx, "×", ctrlPx, dark, "Hide taskbar temporarily") {
            scope.launch { repo.update { st -> st.copy(temporarilyHidden = true) } }
        })
        root.addView(strip)

        if (pages > 1) {
            val dots = LinearLayout(ctx).apply {
                orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            repeat(pages) { i ->
                dots.addView(if (horizontal) dot(ctx, i == page, dark) else dotVertical(ctx, i == page, dark))
            }
            root.addView(dots)
        }

        if (recentEntries.isNotEmpty() && !s.pinnedBar) { // pinned bar stays slim and predictable
            val recentRow = LinearLayout(ctx).apply {
                orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = "Recent apps launched from this dock"
                alpha = 0.9f
                val lp = LinearLayout.LayoutParams(
                    ViewGroupLP.WRAP, ViewGroupLP.WRAP
                )
                if (horizontal) lp.topMargin = gapPx else lp.leftMargin = gapPx
                layoutParams = lp
            }
            val small = (iconPx * 0.7f).roundToInt()
            recentEntries.forEachIndexed { i, app ->
                val icon = makeIcon(ctx, app.icon, small, dark, "Recent: ${app.label}",
                    onClick = { launchApp(app.packageName) },
                    onLongClick = { scope.launch { repo.removeRecent(app.packageName) } })
                (icon.layoutParams as LinearLayout.LayoutParams).apply {
                    if (i > 0) { if (horizontal) leftMargin = gapPx / 2 else topMargin = gapPx / 2 }
                }
                recentRow.addView(icon)
            }
            root.addView(recentRow)
        }

        // Measure, then place next to the chosen edge.
        val maxW = max(1, r.width() - 2 * margin)
        val maxH = max(1, r.height() - 2 * margin)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val w = if (s.pinnedBar) r.width() - 2 * margin else root.measuredWidth
        val h = root.measuredHeight
        val lp = baseParams(w, h, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)

        when (s.edge) {
            DockEdge.TOP, DockEdge.BOTTOM -> {
                lp.x = when (s.align) {
                    DockAlign.START -> r.left + margin
                    DockAlign.CENTER -> r.left + (r.width() - w) / 2
                    DockAlign.END -> r.right - w - margin
                }.coerceIn(r.left, max(r.left, r.right - w))
                lp.y = if (s.edge == DockEdge.TOP) r.top + margin else r.bottom - h - margin
            }
            DockEdge.LEFT, DockEdge.RIGHT -> {
                val range = max(0, r.height() - h)
                lp.y = (r.top + range * s.handleOffsetFraction).roundToInt()
                lp.x = if (s.edge == DockEdge.LEFT) r.left + margin else r.right - w - margin
            }
        }

        wm.addView(root, lp)
        dockView = root
        scheduleAutoCollapse()
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(collapseRunnable)
        val secs = settings.autoCollapseSeconds
        if (expanded && secs > 0 && !settings.pinnedBar) handler.postDelayed(collapseRunnable, secs * 1000L)
    }

    private fun removeDock() {
        handler.removeCallbacks(collapseRunnable)
        dockView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        dockView = null
    }

    private fun cycleLayout() {
        WindowLauncher.resetSlots()
        scope.launch {
            repo.update {
                val all = WindowMode.values()
                it.copy(windowMode = all[(it.windowMode.ordinal + 1) % all.size])
            }
        }
    }

    private fun openMain() {
        try {
            ctx.startActivity(
                android.content.Intent(ctx, com.inwekobong.edgetaskbar.ui.AppPickerActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            collapse()
        } catch (_: Exception) { }
    }

    private fun launchApp(pkg: String) {
        val ok = WindowLauncher.launch(ctx, settings, pkg)
        if (ok) {
            scope.launch { repo.addRecent(pkg) }
            if (settings.collapseAfterLaunch) collapse() else scheduleAutoCollapse()
        } else {
            Toast.makeText(ctx, com.inwekobong.edgetaskbar.R.string.launch_failed, Toast.LENGTH_SHORT).show()
            if (!catalog.isInstalledLaunchable(pkg)) {
                // Stale pin: uninstalled, disabled, or no longer launchable.
                catalog.refresh()
                scope.launch { repo.unpin(pkg); repo.removeRecent(pkg) }
            }
        }
    }

    // ---- keyboard probe (best effort) ---------------------------------------------------
    // A 1px wide, full-height, non-touchable window. If the platform resizes it or reports IME
    // insets for it, we treat the keyboard as visible. Not every device/IME reports this for
    // overlay windows, so the dock simply stays as-is when no signal arrives. We never read
    // keystrokes or other apps' content.

    private fun addProbe() {
        if (probeView != null) return
        val v = View(ctx)
        val lp = WindowManager.LayoutParams(
            1, WindowManager.LayoutParams.MATCH_PARENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        var baseHeight = 0
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            if (h > baseHeight) baseHeight = h
            val old = oldBottom - oldTop
            if (old != h && baseHeight > 0) setIme(h < baseHeight * 0.8f)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener { view, insets ->
                setIme(insets.isVisible(WindowInsets.Type.ime()))
                view.onApplyWindowInsets(insets)
            }
        }
        try { wm.addView(v, lp); probeView = v } catch (_: Exception) { }
    }

    private fun setIme(visible: Boolean) {
        if (visible == imeVisible) return
        imeVisible = visible
        // Debounce so keyboard animation does not flicker the overlay.
        handler.removeCallbacks(imeRunnable)
        handler.postDelayed(imeRunnable, 200)
    }

    private fun removeProbe() {
        probeView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        probeView = null
    }

    private object ViewGroupLP {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
