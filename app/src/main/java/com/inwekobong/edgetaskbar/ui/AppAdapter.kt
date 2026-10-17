package com.inwekobong.edgetaskbar.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.inwekobong.edgetaskbar.R
import com.inwekobong.edgetaskbar.databinding.ItemAppBinding

class AppAdapter(
    private val onToggle: (PickerItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    var items: List<PickerItem> = emptyList()
        private set
    var reorderable = false
        private set

    init { setHasStableIds(true) }

    fun submit(newItems: List<PickerItem>, canReorder: Boolean) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].app.packageName == newItems[b].app.packageName
            override fun areContentsTheSame(a: Int, b: Int) =
                old[a].pinned == newItems[b].pinned && canReorder == reorderable
        })
        items = newItems
        reorderable = canReorder
        diff.dispatchUpdatesTo(this)
    }

    /** Called while dragging; keeps the list in the dragged order without a diff. */
    fun moveItem(from: Int, to: Int) {
        val m = items.toMutableList()
        m.add(to, m.removeAt(from))
        items = m
        notifyItemMoved(from, to)
    }

    override fun getItemId(position: Int): Long = items[position].app.packageName.hashCode().toLong()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.b.icon.setImageDrawable(item.app.icon)
        h.b.label.text = item.app.label
        h.b.packageName.text = item.app.packageName
        h.b.pinButton.setText(if (item.pinned) R.string.action_unpin else R.string.action_pin)
        h.b.pinButton.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onToggle(items[p])
        }
        h.b.dragHandle.visibility = if (reorderable) View.VISIBLE else View.GONE
        h.b.dragHandle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(h)
            false
        }
    }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)
}
