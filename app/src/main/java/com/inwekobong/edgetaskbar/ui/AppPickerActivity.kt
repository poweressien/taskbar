package com.inwekobong.edgetaskbar.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.AppCatalog
import com.inwekobong.edgetaskbar.data.AppEntry
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.overlay.dp
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Browse + search installed launchable apps, pin/unpin with one tap, and reorder pins by drag.
 * Only apps visible through the manifest launcher <queries> are listed; Android may hide others.
 */
class AppPickerActivity : AppCompatActivity() {

    private data class Row(val pkg: String, val label: String, val icon: Drawable?, val stale: Boolean)

    private lateinit var repo: SettingsRepository
    private lateinit var catalog: AppCatalog
    private val adapter = RowAdapter()
    private var all: List<AppEntry> = emptyList()
    private var pinned: List<String> = emptyList()
    private var query = ""
    private var pinnedMode = false
    private var loaded = false

    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var modeBtn: android.widget.Button
    private lateinit var search: EditText
    private lateinit var searchWrap: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository.get(this)
        catalog = AppCatalog.get(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0) }
        root.addView(text("Pinned apps", 24f, true))

        searchWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refresh() }
            })
        }
        val clear = button("✕") { search.setText("") }.apply { contentDescription = "Clear search" }
        searchWrap.addView(search); searchWrap.addView(clear)
        root.addView(searchWrap)

        modeBtn = button("Show pinned order (drag to reorder)") {
            pinnedMode = !pinnedMode
            search.setText("")
            refresh()
        }
        root.addView(modeBtn)

        progress = ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL } }
        empty = text("", 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0); visibility = View.GONE }
        root.addView(progress); root.addView(empty)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@AppPickerActivity.adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(rv)
        setContentView(root)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getMovementFlags(r: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (pinnedMode) super.getMovementFlags(r, vh) else 0

            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                Collections.swap(adapter.items, vh.bindingAdapterPosition, t.bindingAdapterPosition)
                adapter.notifyItemMoved(vh.bindingAdapterPosition, t.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val row = adapter.items.getOrNull(vh.bindingAdapterPosition) ?: return
                lifecycleScope.launch { repo.unpin(row.pkg) }
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                if (pinnedMode) lifecycleScope.launch { repo.setPinned(adapter.items.map { it.pkg }) }
            }
        }).attachToRecyclerView(rv)

        lifecycleScope.launch {
            all = catalog.load(true)
            loaded = true
            refresh()
        }
        lifecycleScope.launch {
            repo.pinned.collect { pinned = it; refresh() }
        }
    }

    private fun refresh() {
        progress.visibility = if (loaded) View.GONE else View.VISIBLE
        searchWrap.visibility = if (pinnedMode) View.GONE else View.VISIBLE
        modeBtn.text = if (pinnedMode) "Browse all apps" else "Show pinned order (drag to reorder)"
        if (!loaded) return

        val rows: List<Row> = if (pinnedMode) {
            pinned.map { p ->
                val e = all.firstOrNull { it.packageName == p }
                if (e != null) Row(p, e.label, e.icon, false) else Row(p, p, null, true)
            }
        } else {
            val q = query.trim().lowercase()
            all.filter { q.isEmpty() || it.label.lowercase().contains(q) }
                .map { Row(it.packageName, it.label, it.icon, false) }
        }
        adapter.items = rows.toMutableList()
        adapter.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (pinnedMode) "Nothing pinned yet. Browse apps and tap Pin." else getString(R.string.no_results)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        var items: MutableList<Row> = mutableListOf()

        inner class VH(val root: LinearLayout, val icon: ImageView, val label: TextView, val action: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
            val label = TextView(ctx).apply {
                textSize = 16f
                setPadding(dp(14), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val action = TextView(ctx).apply {
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(0xFF0067C0.toInt())
            }
            row.addView(icon); row.addView(label); row.addView(action)
            return VH(row, icon, label, action)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = items[position]
            h.icon.setImageDrawable(row.icon)
            h.label.text = if (row.stale) "${row.label} (not available)" else row.label
            val isPinned = row.pkg in pinned
            h.action.text = when {
                pinnedMode -> "Unpin"
                isPinned -> "Pinned ✓"
                else -> "Pin"
            }
            h.action.setOnClickListener {
                lifecycleScope.launch {
                    if (isPinned || pinnedMode) repo.unpin(row.pkg) else repo.pin(row.pkg) // pin() blocks duplicates
                }
            }
            h.root.setOnLongClickListener {
                catalog.openAppInfo(row.pkg); true
            }
            if (row.stale && !pinnedMode) h.action.isEnabled = false
        }
    }
}
