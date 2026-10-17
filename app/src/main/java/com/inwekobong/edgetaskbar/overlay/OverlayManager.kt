package com.inwekobong.edgetaskbar.overlay

import android.content.Context
import android.content.Intent
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
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.DockAlign
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.data.ListOps
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.data.WindowMode
import com.inwekobong.edgetaskbar.ui.AppPickerActivity
import com.inwekobong.edgetaskbar.windows.WindowLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns every WindowManager view of the taskbar. At most three small windows exist at a time:
 *  - the handle (minimized state), or the dock (expanded state),
 *  - a 1px, non-touchable probe used for best-effort keyboard detection.
 * No window is screen-sized and touchable, so everything outside the dock stays usable.
 *
 * [windowCtx] must be a visual context (a window context on Android 11+) so metrics and
 * configuration follow rotation; [appCtx] is used for toasts and starting activities.
 */
class OverlayManager(
    private val appCtx: Context,
    private val windowCtx: Context,
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val catalog: AppCatalog,
    private val onPermissionLost: () -> Unit,
) {
    private enum class Shown { NONE, HANDLE, DOCK }

    private val wm = windowCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var settings = TaskbarSettings()
    private var pinned: List<String> = emptyList()
    private var recents: List<String> = emptyList()

    private var handleView: View? = null
    private var dockView: View? = null
    private var probeView: View? = null
    private var shown = Shown.NONE

    private var started = false
    private var initialized = false
    private var expanded = false
    private var imeVisible = false
    private var page = 0
    private var dragOriginX = 0
    private var dragOriginY = 0

    private val autoCollapseRunnable = Runnable { autoCollapse() }
    private val imeRunnable = Runnable { applyState() }
    private val configRunnable = Runnable { applyState() }

    // ---- public API ---------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        addProbe()
    }

    fun update(s: TaskbarSettings, pins: List<String>, recent: List<String>) {
        val wasPinned = settings.pinnedBar
        settings = s
        pinned = pins
        recents = recent
        if (!initialized) {
            initialized = true
            expanded = s.pinnedBar
        } else if (s.pinnedBar && !wasPinned) {
            expanded = true
        }
        applyState()
    }

    /** Re-reads the app list (after an install or uninstall) and redraws. */
    fun refresh() = applyState()

    /** Rotation, folding and multi-window changes: wait a moment so the new metrics are in place. */
    fun onConfigurationChanged() {
        handler.removeCallbacks(configRunnable)
        handler.postDelayed(configRunnable, 120)
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        removeHandle()
        removeDock()
        removeProbe()
        shown = Shown.NONE
    }

    // ---- state machine ------------------------------------------------------------------

    private fun expand() {
        if (expanded) return
        expanded = true
        page = 0
        applyState()
    }

    /** User pressed minimize: always allowed, even for the pinned bar. */
    private fun minimize() {
        if (!expanded) return
        expanded = false
        handler.removeCallbacks(autoCollapseRunnable)
        applyState()
    }

    /** Timer, outside tap or launch: never touches the pinned bar. */
    private fun autoCollapse() {
        if (settings.pinnedBar) return
        minimize()
    }

    private fun applyState() {
        if (!started) return
        if (!Settings.canDrawOverlays(appCtx)) {
            stop()
            onPermissionLost()
            return
        }
        val suppressed = settings.temporarilyHidden || (settings.hideOnKeyboard && imeVisible)
        try {
            if (suppressed) {
                removeHandle()
                removeDock()
                shown = Shown.NONE
            } else if (expanded) {
                showDock()
            } else {
                showHandle()
            }
        } catch (e: WindowManager.BadTokenException) {
            stop()
            onPermissionLost()
        } catch (e: SecurityException) {
            stop()
            onPermissionLost()
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Toast.makeText(appCtx, appCtx.getString(R.string.overlay_error, msg), Toast.LENGTH_LONG).show()
        }
    }

    private fun baseParams(w: Int, h: Int, extraFlags: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            extraFlags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "EdgeTaskbar"
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun animateIn(v: View, edge: DockEdge) {
        val d = windowCtx.dpf(16f)
        v.alpha = 0f
        when (edge) {
            DockEdge.BOTTOM -> v.translationY = d
            DockEdge.TOP -> v.translationY = -d
            DockEdge.LEFT -> v.translationX = -d
            DockEdge.RIGHT -> v.translationX = d
        }
        v.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(180).start()
    }

    // ---- handle -------------------------------------------------------------------------

    private fun showHandle() {
        val s = settings.effective()
        val st = dockStyle(windowCtx, s)
        val horizontal = s.edge.isHorizontal
        val thick = windowCtx.dp(24)
        val length = windowCtx.dp(s.handleLengthDp)
        val w = if (horizontal) length else thick
        val h = if (horizontal) thick else length
        val area = Geometry.usableRect(windowCtx, wm)
        val lp = baseParams(w, h, 0)
        placeHandle(lp, s.edge, area, w, h, s.handleOffsetFraction)
        dragOriginX = lp.x
        dragOriginY = lp.y

        val view = HandleView(
            windowCtx, horizontal, st,
            onTap = { expand() },
            onDrag = { dx, dy -> dragHandle(lp, s.edge, area, w, h, dx, dy) },
            onDragEnd = { snapHandle(lp, s.edge, area, w, h) },
        )
        view.contentDescription = windowCtx.getString(R.string.dock_open_desc)

        val animate = shown != Shown.HANDLE
        removeDock()
        removeHandle()
        wm.addView(view, lp)
        handleView = view
        shown = Shown.HANDLE
        if (animate) animateIn(view, s.edge)
    }

    private fun placeHandle(lp: WindowManager.LayoutParams, edge: DockEdge, area: Rect, w: Int, h: Int, frac: Float) {
        if (edge.isHorizontal) {
            lp.x = area.left + (max(0, area.width() - w) * frac).roundToInt()
            lp.y = if (edge == DockEdge.TOP) area.top else area.bottom - h
        } else {
            lp.y = area.top + (max(0, area.height() - h) * frac).roundToInt()
            lp.x = if (edge == DockEdge.LEFT) area.left else area.right - w
        }
    }

    private fun dragHandle(lp: WindowManager.LayoutParams, edge: DockEdge, area: Rect, w: Int, h: Int, dx: Float, dy: Float) {
        val v = handleView ?: return
        if (edge.isHorizontal) {
            lp.x = (dragOriginX + dx.roundToInt()).coerceIn(area.left, max(area.left, area.right - w))
        } else {
            lp.y = (dragOriginY + dy.roundToInt()).coerceIn(area.top, max(area.top, area.bottom - h))
        }
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    /** Snaps to the nearest quarter of the edge and remembers the position. */
    private fun snapHandle(lp: WindowManager.LayoutParams, edge: DockEdge, area: Rect, w: Int, h: Int) {
        val raw = if (edge.isHorizontal) {
            (lp.x - area.left).toFloat() / max(1, area.width() - w)
        } else {
            (lp.y - area.top).toFloat() / max(1, area.height() - h)
        }
        val snapped = ((raw.coerceIn(0f, 1f)) * 4f).roundToInt() / 4f
        placeHandle(lp, edge, area, w, h, snapped)
        handleView?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) { } }
        dragOriginX = lp.x
        dragOriginY = lp.y
        scope.launch { repo.update { it.copy(handleOffsetFraction = snapped) } }
    }

    private fun removeHandle() {
        handleView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        handleView = null
    }

    // ---- dock ---------------------------------------------------------------------------

    private fun showDock() {
        val s = settings.effective()
        val st = dockStyle(windowCtx, s)
        val horizontal = s.edge.isHorizontal
        val area = Geometry.usableRect(windowCtx, wm)
        val margin = windowCtx.dp(if (s.pinnedBar) 6 else 8)

        val entries = pinned.mapNotNull { catalog.find(it) }
        val recentEntries = if (s.pinnedBar) emptyList() else
            recents.mapNotNull { catalog.find(it) }.take(s.maxVisibleIcons)

        // How many icons fit on one page along the dock's main axis.
        val mainLen = if (horizontal) area.width() else area.height()
        val controlsLen = 3 * (st.ctrlPx + st.gapPx) + windowCtx.dp(1) + st.gapPx
        val avail = mainLen - 2 * margin - 2 * st.padPx - controlsLen
        val fit = max(1, avail / (st.iconPx + st.gapPx))
        val pageSize = max(1, min(s.maxVisibleIcons, fit))
        val pages = ListOps.pageCount(entries.size, pageSize)
        page = page.coerceIn(0, pages - 1)

        val root = DockRoot(windowCtx, onActivity = { scheduleAutoCollapse() }, onOutside = { autoCollapse() })
        root.orientation = if (horizontal) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER
        root.background = roundedBg(st.bgColor, st.radiusPx, st.strokePx, st.strokeColor)
        root.setPadding(st.padPx, st.padPx, st.padPx, st.padPx)

        // Main strip: [icons] | [layout] [minimize] [hide]
        val strip = LinearLayout(windowCtx)
        strip.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        strip.gravity = Gravity.CENTER

        val icons = SwipeLayout(windowCtx, horizontal) { forward -> changePage(forward, pages) }
        icons.gravity = Gravity.CENTER
        if (entries.isEmpty()) {
            icons.addView(iconButton(windowCtx, R.drawable.ic_add, st.iconPx, st,
                windowCtx.getString(R.string.dock_pin_apps_desc)) { openPicker() })
        }
        entries.drop(page * pageSize).take(pageSize).forEachIndexed { i, app ->
            val v = appIconView(windowCtx, app, st.iconPx, st,
                onClick = { launchApp(app.packageName) },
                onLongClick = { catalog.openAppInfo(app.packageName); autoCollapse() })
            if (i > 0) v.gapBefore(horizontal, st.gapPx)
            icons.addView(v)
        }
        strip.addView(icons)

        val sep = divider(windowCtx, st, horizontal)
        sep.gapBefore(horizontal, st.gapPx)
        (sep.layoutParams as LinearLayout.LayoutParams).apply {
            if (horizontal) marginEnd = st.gapPx else bottomMargin = st.gapPx
        }
        strip.addView(sep)

        val modeLabel = windowCtx.getString(windowModeLabel(s.windowMode))
        strip.addView(iconButton(windowCtx, windowModeIcon(s.windowMode), st.ctrlPx, st,
            windowCtx.getString(R.string.dock_layout_desc, modeLabel)) { cycleLayout() })
        val minimizeBtn = iconButton(windowCtx, R.drawable.ic_minimize, st.ctrlPx, st,
            windowCtx.getString(R.string.dock_minimize_desc)) { minimize() }
        minimizeBtn.gapBefore(horizontal, st.gapPx)
        strip.addView(minimizeBtn)
        val hideBtn = iconButton(windowCtx, R.drawable.ic_eye_off, st.ctrlPx, st,
            windowCtx.getString(R.string.dock_hide_desc)) {
            scope.launch { repo.update { it.copy(temporarilyHidden = true) } }
        }
        hideBtn.gapBefore(horizontal, st.gapPx)
        strip.addView(hideBtn)
        root.addView(strip)

        if (pages > 1) {
            val dots = LinearLayout(windowCtx)
            dots.orientation = strip.orientation
            dots.gravity = Gravity.CENTER
            repeat(pages) { i -> dots.addView(pageDot(windowCtx, i == page, st, horizontal)) }
            root.addView(dots)
        }

        if (recentEntries.isNotEmpty()) {
            val row = LinearLayout(windowCtx)
            row.orientation = strip.orientation
            row.gravity = Gravity.CENTER
            val small = (st.iconPx * 0.7f).roundToInt()
            recentEntries.forEachIndexed { i, app ->
                val v = appIconView(windowCtx, app, small, st,
                    onClick = { launchApp(app.packageName) },
                    onLongClick = { scope.launch { repo.removeRecent(app.packageName) } })
                if (i > 0) v.gapBefore(horizontal, st.gapPx / 2)
                row.addView(v)
            }
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { if (horizontal) it.topMargin = st.gapPx else it.marginStart = st.gapPx })
        }

        // Measure, then place against the chosen edge.
        val maxW = max(1, area.width() - 2 * margin)
        val maxH = max(1, area.height() - 2 * margin)
        val widthMode = if (s.pinnedBar) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
        root.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, widthMode),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val w = root.measuredWidth
        val h = root.measuredHeight
        val flags = if (s.pinnedBar) 0 else WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val lp = baseParams(w, h, flags)
        when (s.edge) {
            DockEdge.TOP, DockEdge.BOTTOM -> {
                lp.x = when (s.align) {
                    DockAlign.START -> area.left + margin
                    DockAlign.CENTER -> area.left + (area.width() - w) / 2
                    DockAlign.END -> area.right - w - margin
                }.coerceIn(area.left, max(area.left, area.right - w))
                lp.y = if (s.edge == DockEdge.TOP) area.top + margin else area.bottom - h - margin
            }
            DockEdge.LEFT, DockEdge.RIGHT -> {
                lp.y = (area.top + max(0, area.height() - h) * s.handleOffsetFraction).roundToInt()
                lp.x = if (s.edge == DockEdge.LEFT) area.left + margin else area.right - w - margin
            }
        }

        val animate = shown != Shown.DOCK
        removeHandle()
        removeDock()
        wm.addView(root, lp)
        dockView = root
        shown = Shown.DOCK
        if (animate) animateIn(root, s.edge)
        scheduleAutoCollapse()
    }

    private fun changePage(forward: Boolean, pages: Int) {
        val next = (page + if (forward) 1 else -1).coerceIn(0, pages - 1)
        if (next != page) {
            page = next
            applyState()
        }
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        val secs = settings.autoCollapseSeconds
        if (expanded && secs > 0 && !settings.pinnedBar) {
            handler.postDelayed(autoCollapseRunnable, secs * 1000L)
        }
    }

    private fun removeDock() {
        handler.removeCallbacks(autoCollapseRunnable)
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

    private fun openPicker() {
        try {
            appCtx.startActivity(Intent(appCtx, AppPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    private fun launchApp(pkg: String) {
        val ok = WindowLauncher.launch(appCtx, settings, pkg)
        if (ok) {
            scope.launch { repo.addRecent(pkg) }
            if (settings.collapseAfterLaunch) autoCollapse() else scheduleAutoCollapse()
        } else {
            Toast.makeText(appCtx, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            if (!catalog.isLaunchable(pkg)) {
                // Stale pin: uninstalled, disabled, or no longer launchable.
                catalog.refresh()
                scope.launch {
                    repo.unpin(pkg)
                    repo.removeRecent(pkg)
                }
            }
        }
    }

    private fun windowModeIcon(m: WindowMode): Int = when (m) {
        WindowMode.FULL -> R.drawable.ic_layout_full
        WindowMode.SPLIT -> R.drawable.ic_layout_split
        WindowMode.QUAD -> R.drawable.ic_layout_quad
        WindowMode.FREEFORM -> R.drawable.ic_layout_free
    }

    private fun windowModeLabel(m: WindowMode): Int = when (m) {
        WindowMode.FULL -> R.string.win_full
        WindowMode.SPLIT -> R.string.win_split
        WindowMode.QUAD -> R.string.win_quad
        WindowMode.FREEFORM -> R.string.win_free
    }

    // ---- keyboard probe (best effort) ---------------------------------------------------
    // A 1px wide, full-height, non-touchable window. If Android resizes it or reports IME insets
    // for it, the keyboard is treated as visible. Not every device or keyboard reports this to an
    // overlay, so when no signal arrives the dock simply stays; the minimize button is the manual
    // fallback. We never read keystrokes or any other app's content.

    private fun addProbe() {
        if (probeView != null) return
        val v = View(windowCtx)
        val lp = WindowManager.LayoutParams(
            1, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        lp.title = "EdgeTaskbar probe"
        var baseHeight = 0
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            if (h > baseHeight) baseHeight = h
            if (oldBottom - oldTop != h && baseHeight > 0) setIme(h < baseHeight * 0.8f)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener { view, insets ->
                setIme(insets.isVisible(WindowInsets.Type.ime()))
                view.onApplyWindowInsets(insets)
            }
        }
        try {
            wm.addView(v, lp)
            probeView = v
        } catch (_: Exception) {
            // Detection is optional; the dock works without it.
        }
    }

    private fun setIme(visible: Boolean) {
        if (visible == imeVisible) return
        imeVisible = visible
        // Debounce so the keyboard animation does not flicker the overlay.
        handler.removeCallbacks(imeRunnable)
        handler.postDelayed(imeRunnable, 200)
    }

    private fun removeProbe() {
        probeView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        probeView = null
    }
}
