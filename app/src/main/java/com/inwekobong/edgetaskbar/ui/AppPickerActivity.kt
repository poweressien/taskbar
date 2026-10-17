package com.inwekobong.edgetaskbar.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.data.SettingsRepository
import com.inwekobong.edgetaskbar.databinding.ActivityAppPickerBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding
    private val vm: AppPickerViewModel by viewModels()
    private lateinit var adapter: AppAdapter
    private lateinit var touch: ItemTouchHelper
    private var dragging = false
    private var pins: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarPadding()
        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppAdapter(
            onToggle = { vm.toggle(it.app.packageName, it.pinned) },
            onStartDrag = { touch.startDrag(it) },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = adapter.reorderable

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.reorderable) super.getMovementFlags(rv, vh) else 0

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false
                dragging = true
                adapter.moveItem(f, t)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val item = adapter.items[pos]
                val index = pins.indexOf(item.app.packageName).coerceAtLeast(0)
                vm.unpin(item.app.packageName)
                Snackbar.make(b.root, getString(R.string.snack_unpinned, item.app.label), Snackbar.LENGTH_LONG)
                    .setAction(R.string.snack_undo) { vm.pinAt(index, item.app.packageName) }
                    .show()
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (dragging) {
                    dragging = false
                    vm.reorder(adapter.items.map { it.app.packageName }, pins)
                }
            }
        })
        touch.attachToRecyclerView(b.list)

        b.searchInput.doAfterTextChanged { vm.query.value = it?.toString().orEmpty() }
        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) vm.pinnedMode.value = id == R.id.modePinned
        }

        lifecycleScope.launch {
            pins = SettingsRepository.get(this@AppPickerActivity).pinned.first()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SettingsRepository.get(this@AppPickerActivity).pinned.collect { pins = it }
                }
                launch { vm.state.collect { render(it) } }
            }
        }
    }

    private fun render(s: PickerState) {
        b.modePinned.text = getString(R.string.picker_pinned, s.pinnedCount)
        b.progress.visibility = if (s.loading) View.VISIBLE else View.GONE
        if (!dragging) adapter.submit(s.items, s.pinnedMode && s.query.isBlank())
        val empty = !s.loading && s.items.isEmpty()
        b.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) b.emptyText.setText(if (s.pinnedMode && s.query.isBlank()) R.string.picker_empty_pinned else R.string.picker_empty_search)
    }
}
