package com.inwekobong.edgetaskbar.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.databinding.ActivityMainBinding
import com.inwekobong.edgetaskbar.databinding.RowItemBinding
import com.inwekobong.edgetaskbar.nav.NavAccessibilityService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private var last: TaskbarSettings? = null
    private var lastPins = 0

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val homeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarPadding()

        b.chooseAppsButton.setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }
        b.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.primaryButton.setOnClickListener { onPrimary() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.combine(vm.pinnedCount) { s, n -> s to n }.collect { (s, n) ->
                    last = s
                    lastPins = n
                    render()
                }
            }
        }
    }

    /** Permissions are changed in other screens, so re-check every time we come back. */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun overlayGranted() = Settings.canDrawOverlays(this)

    private fun notifGranted() =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun onPrimary() {
        val s = last ?: return
        when {
            !overlayGranted() -> openOverlaySettings()
            s.enabled -> vm.stop()
            else -> {
                if (!notifGranted()) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.start()
            }
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        val s = last ?: return
        val granted = overlayGranted()
        val (icon, title, detail, tint, button) = when {
            !granted -> Status(R.drawable.ic_warning, R.string.status_perm_title, R.string.status_perm_detail,
                R.color.et_warn, R.string.btn_grant)
            s.enabled -> Status(R.drawable.ic_check_circle, R.string.status_on_title, R.string.status_on_detail,
                R.color.et_ok, R.string.btn_stop)
            else -> Status(R.drawable.ic_circle_outline, R.string.status_off_title, R.string.status_off_detail,
                R.color.et_text_secondary, R.string.btn_start)
        }
        b.statusIcon.setImageResource(icon)
        ImageViewCompat.setImageTintList(b.statusIcon,
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, tint)))
        b.statusTitle.setText(title)
        b.statusDetail.setText(detail)
        b.primaryButton.setText(button)
        renderChecklist(s, granted)
    }

    private data class Status(val icon: Int, val title: Int, val detail: Int, val tint: Int, val button: Int)

    private fun renderChecklist(s: TaskbarSettings, overlayOk: Boolean) {
        b.checklist.removeAllViews()
        fun row(
            iconRes: Int, title: Int, subtitle: String, ok: Boolean,
            actionText: Int?, onAction: (() -> Unit)?,
        ) {
            val r = RowItemBinding.inflate(LayoutInflater.from(this), b.checklist, false)
            r.icon.setImageResource(if (ok) R.drawable.ic_check_circle else iconRes)
            ImageViewCompat.setImageTintList(r.icon, android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (ok) R.color.et_ok else R.color.et_text_secondary)))
            r.title.setText(title)
            r.subtitle.text = subtitle
            if (actionText != null && onAction != null) {
                r.action.setText(actionText)
                r.action.setOnClickListener { onAction() }
            } else {
                r.action.visibility = android.view.View.GONE
            }
            b.checklist.addView(r.root)
        }

        row(R.drawable.ic_warning, R.string.check_overlay_title,
            getString(if (overlayOk) R.string.check_overlay_ok else R.string.check_overlay_missing),
            overlayOk, if (overlayOk) null else R.string.action_allow) { openOverlaySettings() }

        val notifNeeded = Build.VERSION.SDK_INT >= 33
        val notifOk = notifGranted()
        row(R.drawable.ic_circle_outline, R.string.check_notif_title,
            getString(when { !notifNeeded -> R.string.check_notif_na; notifOk -> R.string.check_notif_ok; else -> R.string.check_notif_missing }),
            notifOk, if (notifOk) null else R.string.action_allow) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        row(R.drawable.ic_pin, R.string.check_pins_title,
            if (lastPins > 0) getString(R.string.check_pins_count, lastPins) else getString(R.string.check_pins_none),
            lastPins > 0, R.string.action_choose) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        val navOn = NavAccessibilityService.running && s.navTop
        row(R.drawable.ic_circle_outline, R.string.check_nav_title,
            getString(if (navOn) R.string.check_nav_ok else R.string.check_nav_off),
            navOn, if (navOn) null else R.string.action_enable) {
            vm.setNavTop(true)
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        val home = isDefaultHome()
        row(R.drawable.ic_home, R.string.check_home_title,
            getString(if (home) R.string.check_home_ok else R.string.check_home_off),
            home, if (home) null else R.string.action_set) { requestHome() }
    }

    private fun requestHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    homeRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
