package com.inwekobong.edgetaskbar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.AppEntry
import com.inwekobong.edgetaskbar.data.ListOps
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import com.inwekobong.edgetaskbar.databinding.ActivityHomeBinding
import com.inwekobong.edgetaskbar.databinding.ItemHomeAppBinding
import com.inwekobong.edgetaskbar.overlay.OverlayService
import com.inwekobong.edgetaskbar.overlay.dp
import com.inwekobong.edgetaskbar.windows.WindowLauncher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Optional home screen: wallpaper, clock, search and an app grid, with the taskbar on top. */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var repo: SettingsRepository
    private lateinit var catalog: AppCatalog
    private val adapter = HomeAdapter()
    private var all: List<AppEntry> = emptyList()
    private var settings = TaskbarSettings()
    private var pins: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repo = SettingsRepository.get(this)
        catalog = AppCatalog.get(this)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarPadding()

        // A home screen has nowhere to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        b.grid.layoutManager = GridLayoutManager(this, 4)
        b.grid.adapter = adapter
        b.settingsButton.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        b.searchInput.doAfterTextChanged { applyFilter() }
        b.permHint.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.settings.combine(repo.pinned) { s, p -> s to p }.collect { (s, p) ->
                    settings = s
                    pins = p
                    // Leave room so the grid is not hidden behind the bars.
                    val bottom = if (s.pinnedBar) dp(s.iconSizeDp + 2 * s.paddingDp + 16) else 0
                    val top = if (s.navTop) dp(44) else 0
                    b.grid.setPadding(b.grid.paddingLeft, top, b.grid.paddingRight, bottom)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        b.permHint.visibility = if (granted) View.GONE else View.VISIBLE
        lifecycleScope.launch {
            all = catalog.load()
            applyFilter()
            val s = repo.current()
            if (granted && s.enabled && !OverlayService.running) {
                try { OverlayService.start(this@HomeActivity) } catch (_: Exception) { }
            }
        }
    }

    private fun applyFilter() {
        val q = b.searchInput.text?.toString().orEmpty()
        adapter.submit(ListOps.filterByLabel(all, q) { it.label })
    }

    private fun showMenu(anchor: View, app: AppEntry) {
        val isPinned = app.packageName in pins
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.home_menu_info)
        menu.menu.add(0, 2, 1, if (isPinned) R.string.home_menu_unpin else R.string.home_menu_pin)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> catalog.openAppInfo(app.packageName)
                2 -> lifecycleScope.launch {
                    if (isPinned) repo.unpin(app.packageName) else repo.pin(app.packageName)
                }
            }
            true
        }
        menu.show()
    }

    private inner class HomeAdapter : RecyclerView.Adapter<HomeAdapter.VH>() {
        private var items: List<AppEntry> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<AppEntry>) { items = list; notifyDataSetChanged() }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHomeAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val app = items[position]
            h.b.icon.setImageDrawable(app.icon)
            h.b.label.text = app.label
            h.b.root.setOnClickListener {
                lifecycleScope.launch {
                    val s = repo.settings.first()
                    if (WindowLauncher.launch(this@HomeActivity, s, app.packageName)) repo.addRecent(app.packageName)
                    else android.widget.Toast.makeText(this@HomeActivity, R.string.launch_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            h.b.root.setOnLongClickListener { showMenu(it, app); true }
        }

        inner class VH(val b: ItemHomeAppBinding) : RecyclerView.ViewHolder(b.root)
    }
}
