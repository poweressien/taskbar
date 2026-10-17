package com.inwekobong.edgetaskbar.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.DockAlign
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.data.ThemeMode
import com.inwekobong.edgetaskbar.data.WindowMode
import com.inwekobong.edgetaskbar.databinding.ActivitySettingsBinding
import com.inwekobong.edgetaskbar.databinding.RowChoiceBinding
import com.inwekobong.edgetaskbar.databinding.RowItemBinding
import com.inwekobong.edgetaskbar.databinding.RowSliderBinding
import com.inwekobong.edgetaskbar.databinding.RowSwitchBinding
import com.inwekobong.edgetaskbar.databinding.SectionCardBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val vm: SettingsViewModel by viewModels()

    private var latest = TaskbarSettings()
    private var sliding = false
    private val refreshers = mutableListOf<(TaskbarSettings) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarPadding()
        b.toolbar.setNavigationOnClickListener { finish() }

        latest = vm.settings.value
        buildSections()
        refresh(latest)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.collect { s ->
                    if (!sliding) {
                        latest = s
                        refresh(s)
                    }
                }
            }
        }
    }

    private fun refresh(s: TaskbarSettings) {
        b.preview.render(s)
        refreshers.forEach { it(s) }
    }

    // ---- sections -----------------------------------------------------------------------

    private fun buildSections() {
        // Mode
        val modeBody = section(R.string.sec_mode)
        val edgeRow = choiceRow(modeBody, R.string.mode_position_title)
        val alignRow = choiceRow(modeBody, R.string.mode_align_title)
        val handleRow = sliderRow(modeBody, R.string.mode_handle_title, 36, 120, "dp",
            { it.handleLengthDp }) { s, v -> s.copy(handleLengthDp = v) }
        val pinnedRow = switchRow(modeBody, R.string.mode_pinned_title, R.string.mode_pinned_sub,
            { it.pinnedBar }) { s, v -> s.copy(pinnedBar = v) }
        modeBody.removeView(pinnedRow)
        modeBody.addView(pinnedRow, 0)

        val edgeGroup = toggleGroup(edgeRow, listOf(
            R.string.edge_top to DockEdge.TOP, R.string.edge_bottom to DockEdge.BOTTOM,
            R.string.edge_left to DockEdge.LEFT, R.string.edge_right to DockEdge.RIGHT,
        ), { it.edge }) { s, v -> s.copy(edge = v) }
        val alignGroup = toggleGroup(alignRow, listOf(
            R.string.align_start to DockAlign.START, R.string.align_center to DockAlign.CENTER,
            R.string.align_end to DockAlign.END,
        ), { it.align }) { s, v -> s.copy(align = v) }
        refreshers += { s ->
            // The pinned bar is always at the bottom and centered, so those choices only apply to the floating dock.
            edgeRow.root.visibility = if (s.pinnedBar) View.GONE else View.VISIBLE
            alignRow.root.visibility = if (s.pinnedBar || !s.edge.isHorizontal) View.GONE else View.VISIBLE
            handleRow.visibility = if (s.pinnedBar) View.GONE else View.VISIBLE
        }
        check(edgeGroup.childCount == 4 && alignGroup.childCount == 3)

        // Icons
        val iconBody = section(R.string.sec_icons)
        sliderRow(iconBody, R.string.icon_size_title, TaskbarSettings.MIN_ICON, TaskbarSettings.MAX_ICON, "dp",
            { it.iconSizeDp }) { s, v -> s.copy(iconSizeDp = v) }
        sliderRow(iconBody, R.string.icon_spacing_title, 0, 24, "dp",
            { it.spacingDp }) { s, v -> s.copy(spacingDp = v) }
        sliderRow(iconBody, R.string.icon_count_title, 3, 10, "",
            { it.maxVisibleIcons }) { s, v -> s.copy(maxVisibleIcons = v) }

        // Appearance
        val lookBody = section(R.string.sec_look)
        sliderRow(lookBody, R.string.look_opacity_title, 30, 100, "%",
            { it.opacityPercent }) { s, v -> s.copy(opacityPercent = v) }
        sliderRow(lookBody, R.string.look_radius_title, 0, 32, "dp",
            { it.cornerRadiusDp }) { s, v -> s.copy(cornerRadiusDp = v) }
        sliderRow(lookBody, R.string.look_padding_title, 4, 24, "dp",
            { it.paddingDp }) { s, v -> s.copy(paddingDp = v) }
        toggleGroup(choiceRow(lookBody, R.string.look_theme_title), listOf(
            R.string.theme_system to ThemeMode.FOLLOW_SYSTEM, R.string.theme_light to ThemeMode.LIGHT,
            R.string.theme_dark to ThemeMode.DARK,
        ), { it.theme }) { s, v -> s.copy(theme = v) }

        // Windows
        val winBody = section(R.string.sec_windows)
        toggleGroup(choiceRow(winBody, R.string.win_layout_title), listOf(
            R.string.win_full to WindowMode.FULL, R.string.win_split to WindowMode.SPLIT,
            R.string.win_quad to WindowMode.QUAD, R.string.win_free to WindowMode.FREEFORM,
        ), { it.windowMode }) { s, v -> s.copy(windowMode = v) }
        val freeform = packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        noteRow(winBody, if (freeform) R.string.win_note_supported else R.string.win_note_unsupported)
        actionRow(winBody, R.drawable.ic_settings, R.string.win_dev_title, getString(R.string.win_dev_sub),
            R.string.action_open) { openSafely(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }

        // Behavior
        val behBody = section(R.string.sec_behavior)
        switchRow(behBody, R.string.beh_collapse_launch, null,
            { it.collapseAfterLaunch }) { s, v -> s.copy(collapseAfterLaunch = v) }
        sliderRow(behBody, R.string.beh_autocollapse_title, 0, 60, "s",
            { it.autoCollapseSeconds }) { s, v -> s.copy(autoCollapseSeconds = v) }
        switchRow(behBody, R.string.beh_keyboard_title, R.string.beh_keyboard_sub,
            { it.hideOnKeyboard }) { s, v -> s.copy(hideOnKeyboard = v) }
        switchRow(behBody, R.string.beh_boot_title, R.string.beh_boot_sub,
            { it.startOnBoot }) { s, v -> s.copy(startOnBoot = v) }

        // System
        val sysBody = section(R.string.sec_system)
        switchRow(sysBody, R.string.sys_nav_title, R.string.sys_nav_sub,
            { it.navTop }) { s, v ->
            s.copy(navTop = v)
        }
        actionRow(sysBody, R.drawable.ic_home, R.string.sys_home_title, getString(R.string.sys_home_sub),
            R.string.action_set) { openSafely(Intent(Settings.ACTION_HOME_SETTINGS)) }
        actionRow(sysBody, R.drawable.ic_info, R.string.sys_battery_title, getString(R.string.sys_battery_sub),
            R.string.action_open) {
            openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        // Maintenance
        val maintBody = section(R.string.sec_maintenance)
        actionRow(maintBody, R.drawable.ic_close, R.string.maint_clear_title, getString(R.string.maint_clear_sub),
            R.string.action_run) {
            vm.clearRecents { Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show() }
        }
        actionRow(maintBody, R.drawable.ic_pin, R.string.maint_cleanup_title, getString(R.string.maint_cleanup_sub),
            R.string.action_run) {
            vm.removeStalePins { n ->
                Toast.makeText(this, getString(R.string.toast_removed_stale, n), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSafely(i: Intent) {
        try { startActivity(i) } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- row builders -------------------------------------------------------------------

    private fun section(title: Int): ViewGroup {
        val sb = SectionCardBinding.inflate(LayoutInflater.from(this), b.content, false)
        sb.sectionTitle.setText(title)
        b.content.addView(sb.root)
        return sb.sectionBody
    }

    private fun switchRow(
        parent: ViewGroup, title: Int, subtitle: Int?,
        get: (TaskbarSettings) -> Boolean, set: (TaskbarSettings, Boolean) -> TaskbarSettings,
    ): View {
        val r = RowSwitchBinding.inflate(LayoutInflater.from(this), parent, false)
        r.title.setText(title)
        if (subtitle != null) r.subtitle.setText(subtitle) else r.subtitle.visibility = View.GONE
        r.toggle.setOnCheckedChangeListener { _, checked ->
            if (checked != get(latest)) {
                vm.update { set(it, checked) }
                if (title == R.string.sys_nav_title && checked) openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        r.root.setOnClickListener { r.toggle.toggle() }
        refreshers += { s -> if (r.toggle.isChecked != get(s)) r.toggle.isChecked = get(s) }
        parent.addView(r.root)
        return r.root
    }

    private fun sliderRow(
        parent: ViewGroup, title: Int, min: Int, max: Int, unit: String,
        get: (TaskbarSettings) -> Int, set: (TaskbarSettings, Int) -> TaskbarSettings,
    ): View {
        val r = RowSliderBinding.inflate(LayoutInflater.from(this), parent, false)
        r.title.setText(title)
        r.slider.valueFrom = min.toFloat()
        r.slider.valueTo = max.toFloat()
        r.slider.stepSize = 1f
        fun label(v: Int) { r.value.text = if (unit.isEmpty()) "$v" else "$v $unit" }
        r.slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = value.toInt()
            label(v)
            latest = set(latest, v).sanitized()
            b.preview.render(latest) // live preview while dragging; saved when the finger lifts
        }
        r.slider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) { sliding = true }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                sliding = false
                val v = slider.value.toInt()
                vm.update { set(it, v) }
            }
        })
        refreshers += { s ->
            val v = get(s).coerceIn(min, max)
            if (r.slider.value.toInt() != v) r.slider.value = v.toFloat()
            label(v)
        }
        parent.addView(r.root)
        return r.root
    }

    private fun choiceRow(parent: ViewGroup, title: Int): RowChoiceBinding {
        val r = RowChoiceBinding.inflate(LayoutInflater.from(this), parent, false)
        r.title.setText(title)
        parent.addView(r.root)
        return r
    }

    /** One-of-N selector. Selecting the value that is already saved does nothing. */
    private fun <T> toggleGroup(
        row: RowChoiceBinding, options: List<Pair<Int, T>>,
        get: (TaskbarSettings) -> T, set: (TaskbarSettings, T) -> TaskbarSettings,
    ): MaterialButtonToggleGroup {
        val g = MaterialButtonToggleGroup(this)
        g.isSingleSelection = true
        g.isSelectionRequired = true
        val ids = options.map { (label, value) ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.id = View.generateViewId()
            btn.setText(label)
            btn.layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            g.addView(btn)
            btn.id to value
        }
        g.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val value = ids.firstOrNull { it.first == id }?.second ?: return@addOnButtonCheckedListener
            if (value != get(latest)) vm.update { set(it, value) }
        }
        refreshers += { s ->
            val target = ids.firstOrNull { it.second == get(s) }?.first
            if (target != null && g.checkedButtonId != target) g.check(target)
        }
        row.holder.addView(g, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return g
    }

    private fun actionRow(
        parent: ViewGroup, icon: Int, title: Int, subtitle: String, actionText: Int, onClick: () -> Unit,
    ) {
        val r = RowItemBinding.inflate(LayoutInflater.from(this), parent, false)
        r.icon.setImageResource(icon)
        r.icon.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, R.color.et_text_secondary))
        r.title.setText(title)
        r.subtitle.text = subtitle
        r.action.setText(actionText)
        r.action.setOnClickListener { onClick() }
        parent.addView(r.root)
    }

    private fun noteRow(parent: ViewGroup, text: Int) {
        val tv = android.widget.TextView(this)
        tv.setText(text)
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.et_text_secondary))
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, 0, pad, pad / 2)
        parent.addView(tv)
    }
}
