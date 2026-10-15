package com.inwekobong.edgetaskbar.ui

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.AppEntry
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.overlay.OverlayService
import com.inwekobong.edgetaskbar.overlay.dp
import com.inwekobong.edgetaskbar.windows.WindowLauncher
import kotlinx.coroutines.launch

/**
 * Optional home screen. Select EdgeTaskbar as your home app in system settings to use it.
 * Shows your wallpaper and an app grid that stays clear of the pinned bottom bar and the top
 * nav buttons. Apps launched from here follow the chosen window layout.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var repo: SettingsRepository
    private lateinit var catalog: AppCatalog
    private lateinit var grid: RecyclerView
    private lateinit var permHint: TextView
    private val adapter = GridAdapter()
    private var settings = TaskbarSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository.get(this)
        catalog = AppCatalog.get(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(12), dp(4))
        }
        val clock = TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = 34f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gear = text("⚙", 24f, false, Color.WHITE).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            contentDescription = "Taskbar settings"
            setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
        }
        top.addView(clock); top.addView(gear)
        root.addView(top)

        permHint = text("Tap to grant overlay permission so the taskbar can show", 13f, false, Color.WHITE).apply {
            setPadding(dp(20), dp(4), dp(20), dp(8))
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@HomeActivity, MainActivity::class.java)) }
        }
        root.addView(permHint)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = this@HomeActivity.adapter
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(grid)
        setContentView(root)

        // A home screen must not exit on Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        lifecycleScope.launch {
            repo.settings.collect { s ->
                settings = s
                val topPad = if (s.navTop) dp(44) else 0
                val bottomPad = if (s.pinnedBar) dp(s.iconSizeDp + 2 * s.paddingDp + 24) else dp(8)
                root.setPadding(0, topPad, 0, 0)
                grid.setPadding(dp(8), 0, dp(8), bottomPad)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val canDraw = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        permHint.visibility = if (canDraw) View.GONE else View.VISIBLE
        if (canDraw) OverlayService.start(this) // idempotent
        lifecycleScope.launch {
            adapter.items = catalog.load(true)
            adapter.notifyDataSetChanged()
        }
    }

    private fun open(app: AppEntry) {
        if (!WindowLauncher.launch(this, settings, app.packageName)) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        var items: List<AppEntry> = emptyList()

        inner class VH(val root: LinearLayout, val icon: ImageView, val label: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) }
            val label = TextView(ctx).apply {
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(6f, 0f, 1f, Color.BLACK)
                setPadding(0, dp(4), 0, 0)
            }
            col.addView(icon); col.addView(label)
            return VH(col, icon, label)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val app = items[position]
            h.icon.setImageDrawable(app.icon)
            h.label.text = app.label
            h.root.setOnClickListener { open(app) }
            h.root.setOnLongClickListener { catalog.openAppInfo(app.packageName); true }
        }
    }
}
