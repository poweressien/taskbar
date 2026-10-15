package com.inwekobong.edgetaskbar.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.DockAlign
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.data.ThemeMode
import com.inwekobong.edgetaskbar.data.WindowMode
import com.inwekobong.edgetaskbar.nav.NavAccessibilityService
import com.inwekobong.edgetaskbar.windows.WindowLauncher
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.inwekobong.edgetaskbar.overlay.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Every control writes to DataStore; the running service observes it and re-lays out immediately. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository.get(this)
        lifecycleScope.launch { build(repo.current()) }
    }

    private fun change(f: (TaskbarSettings) -> TaskbarSettings) {
        lifecycleScope.launch { repo.update(f) }
    }

    private fun build(s: TaskbarSettings) {
        val col = pageColumn()
        col.addView(text("Settings", 26f, true))

        // Position: visual selector
        col.addView(header("DOCK POSITION"))
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tileViews = DockEdge.values().map { e ->
            EdgeTile(this, e, e == s.edge) { }
        }
        tileViews.forEachIndexed { i, t ->
            val edge = DockEdge.values()[i]
            t.setOnClickListener {
                tileViews.forEach { v -> v.setSelectedTile(v === t) }
                change { it.copy(edge = edge) }
            }
            tiles.addView(t)
        }
        col.addView(tiles)

        col.addView(header("ALIGNMENT (TOP / BOTTOM)"))
        col.addView(radioGroup(DockAlign.values().map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            s.align.ordinal) { idx -> change { it.copy(align = DockAlign.values()[idx]) } })

        col.addView(header("ICONS"))
        col.addView(sliderRow("Icon size", 24, 64, s.iconSizeDp, " dp") { v -> change { it.copy(iconSizeDp = v) } })
        col.addView(sliderRow("Icon spacing", 0, 24, s.spacingDp, " dp") { v -> change { it.copy(spacingDp = v) } })
        col.addView(sliderRow("Max icons per page", 3, 10, s.maxVisibleIcons, "") { v -> change { it.copy(maxVisibleIcons = v) } })

        col.addView(header("DOCK SHAPE"))
        col.addView(sliderRow("Dock padding", 4, 24, s.paddingDp, " dp") { v -> change { it.copy(paddingDp = v) } })
        col.addView(sliderRow("Corner radius", 0, 32, s.cornerRadiusDp, " dp") { v -> change { it.copy(cornerRadiusDp = v) } })
        col.addView(sliderRow("Handle length", 36, 120, s.handleLengthDp, " dp") { v -> change { it.copy(handleLengthDp = v) } })
        col.addView(sliderRow("Background opacity", 30, 100, s.opacityPercent, "%") { v -> change { it.copy(opacityPercent = v) } })

        col.addView(header("APPEARANCE (DOCK ONLY)"))
        col.addView(radioGroup(listOf("Follow system", "Light", "Dark"), s.theme.ordinal) { idx ->
            change { it.copy(theme = ThemeMode.values()[idx]) }
        })

        col.addView(header("BOTTOM BAR, NAV BUTTONS & WINDOWS"))
        col.addView(switchRow("Pin taskbar to bottom (always visible)", s.pinnedBar) { b -> change { it.copy(pinnedBar = b) } })
        col.addView(switchRow("Back / Home / Recents buttons at top", s.navTop) { b ->
            change { it.copy(navTop = b) }
            if (b && NavAccessibilityService.instance == null) {
                Toast.makeText(this, "Turn on EdgeTaskbar in Accessibility settings", Toast.LENGTH_LONG).show()
                openAccessibility()
            }
        })
        col.addView(text("Window layout for launched apps", 14f).apply { setPadding(0, dp(10), 0, 0) })
        col.addView(radioGroup(WindowMode.values().map { it.label }, s.windowMode.ordinal, vertical = true) { idx ->
            WindowLauncher.resetSlots()
            change { it.copy(windowMode = WindowMode.values()[idx]) }
        })
        val freeform = packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        col.addView(text(
            (if (freeform) "This device reports free-form window support. " else
                "This device does not report free-form window support; Split/Quad may still work, free-form may open full screen. ") +
                "Windows are drawn and moved by Android itself (drag the title bar, resize edges). Apps that disable resizing open full screen. " +
                "Whether several apps keep playing audio at once is decided by each app.", 12f))
        col.addView(button("Set EdgeTaskbar as home app") { launchSettings(Settings.ACTION_HOME_SETTINGS) })
        col.addView(button("Open accessibility settings") { openAccessibility() })
        col.addView(button("Open developer options (free-form windows)") {
            launchSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        })
        col.addView(text(
            "Android cannot hide or move its own navigation bar. For a clean bottom, switch System navigation to " +
                "Gestures. On Android 13+, if the accessibility switch is greyed out: Settings > Apps > EdgeTaskbar > " +
                "menu > Allow restricted settings.", 12f))

        col.addView(header("BEHAVIOR"))
        col.addView(switchRow("Collapse after launching an app", s.collapseAfterLaunch) { b -> change { it.copy(collapseAfterLaunch = b) } })
        col.addView(sliderRow("Auto-collapse after (0 = never)", 0, 60, s.autoCollapseSeconds, " s") { v -> change { it.copy(autoCollapseSeconds = v) } })
        col.addView(switchRow("Hide while keyboard is open (best effort)", s.hideOnKeyboard) { b -> change { it.copy(hideOnKeyboard = b) } })
        col.addView(switchRow("Start taskbar after reboot", s.startOnBoot) { b -> change { it.copy(startOnBoot = b) } })
        col.addView(text(
            "Collapse = shrink to the small handle. Hide (× on the dock, or the notification action) removes " +
                "the whole overlay until you tap Show. Keyboard detection and boot start depend on the device " +
                "and manufacturer and are not guaranteed.", 12f))

        col.addView(header("MAINTENANCE"))
        col.addView(text("Recent apps list only shows apps launched from this dock, not system-wide history.", 12f))
        col.addView(button(getString(com.inwekobong.edgetaskbar.R.string.clear_recents)) {
            lifecycleScope.launch { repo.clearRecents() }
        })
        col.addView(button(getString(com.inwekobong.edgetaskbar.R.string.cleanup_stale)) {
            lifecycleScope.launch {
                val catalog = AppCatalog.get(this@SettingsActivity)
                catalog.load(true)
                val cur = repo.pinned.first()
                val alive = cur.filter { catalog.find(it) != null }
                repo.setPinned(alive)
                Toast.makeText(this@SettingsActivity, "Removed ${cur.size - alive.size} stale pin(s)", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(button(getString(com.inwekobong.edgetaskbar.R.string.pick_apps)) {
            startActivity(android.content.Intent(this, AppPickerActivity::class.java))
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun openAccessibility() = launchSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun launchSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun radioGroup(
        labels: List<String>, checked: Int, vertical: Boolean = false, onPick: (Int) -> Unit,
    ): RadioGroup =
        RadioGroup(this).apply {
            orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
            labels.forEachIndexed { i, l ->
                addView(RadioButton(context).apply { id = 1000 + i; text = l; setPadding(0, 0, dp(12), 0) })
            }
            check(1000 + checked)
            setOnCheckedChangeListener { _, id -> onPick(id - 1000) }
        }
}
