package com.inwekobong.edgetaskbar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.overlay.OverlayService
import com.inwekobong.edgetaskbar.overlay.dp
import kotlinx.coroutines.launch

/** Onboarding + start/stop. Rechecks overlay permission every time it resumes. */
class MainActivity : AppCompatActivity() {

    private lateinit var repo: SettingsRepository
    private lateinit var status: TextView
    private lateinit var permCard: LinearLayout
    private lateinit var permError: TextView
    private lateinit var toggle: android.widget.Button
    private var enabled = false
    private var askedOnce = false

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denial is fine */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository.get(this)

        val col = pageColumn()
        col.addView(text("EdgeTaskbar", 28f, true))
        status = text("", 15f).apply { setPadding(0, dp(6), 0, dp(12)) }
        col.addView(status)

        permCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = com.inwekobong.edgetaskbar.overlay.roundedBg(0x140067C0, dp(16).toFloat())
        }
        permCard.addView(text(getString(R.string.perm_title), 17f, true))
        permCard.addView(text(getString(R.string.perm_body)).apply { setPadding(0, dp(8), 0, dp(8)) })
        permCard.addView(text(getString(R.string.notif_perm_body), 13f).apply { setPadding(0, 0, 0, dp(8)) })
        permError = text(getString(R.string.perm_denied), 14f, false, 0xFFB3261E.toInt()).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        permCard.addView(permError)
        permCard.addView(button(getString(R.string.perm_grant)) { askedOnce = true; openOverlaySettings() })
        col.addView(permCard)

        toggle = button(getString(R.string.start_taskbar)) { onToggle() }
        col.addView(toggle)
        col.addView(button(getString(R.string.pick_apps)) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        })
        col.addView(button(getString(R.string.open_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        col.addView(text(
            "Note: Android does not let apps reserve space for a dock. EdgeTaskbar floats above other " +
                "apps, so keep it collapsed when you need the whole screen.", 12f
        ).apply { setPadding(0, dp(16), 0, 0) })

        setContentView(ScrollView(this).apply { addView(col) })

        lifecycleScope.launch {
            repo.settings.collect { s -> enabled = s.enabled; render() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun canDraw() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun render() {
        val ok = canDraw()
        permCard.visibility = if (ok) View.GONE else View.VISIBLE
        permError.visibility = if (!ok && askedOnce) View.VISIBLE else View.GONE
        val running = enabled && ok
        status.text = when {
            !ok -> "Overlay permission is off."
            running -> "Taskbar is running."
            else -> "Taskbar is stopped."
        }
        toggle.text = getString(if (running) R.string.stop_taskbar else R.string.start_taskbar)
        toggle.isEnabled = ok
    }

    private fun onToggle() {
        if (!canDraw()) { render(); return }
        if (enabled) {
            OverlayService.stop(this)
        } else {
            maybeAskNotifications()
            OverlayService.start(this) // only after permission is granted
        }
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }
}
