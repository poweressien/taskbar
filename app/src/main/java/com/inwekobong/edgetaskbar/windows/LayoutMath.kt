package com.inwekobong.edgetaskbar.windows

import com.inwekobong.edgetaskbar.data.WindowMode

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Pure geometry for window slots, kept free of Android types so it is unit-testable. */
object LayoutMath {

    fun slots(mode: WindowMode, area: IntRect, gap: Int = 0): List<IntRect> {
        val h1 = gap / 2
        val h2 = gap - h1
        return when (mode) {
            WindowMode.FULL -> listOf(area)
            WindowMode.SPLIT ->
                if (area.height >= area.width) {
                    val mid = area.top + area.height / 2
                    listOf(
                        IntRect(area.left, area.top, area.right, mid - h1),
                        IntRect(area.left, mid + h2, area.right, area.bottom),
                    )
                } else {
                    val mid = area.left + area.width / 2
                    listOf(
                        IntRect(area.left, area.top, mid - h1, area.bottom),
                        IntRect(mid + h2, area.top, area.right, area.bottom),
                    )
                }
            WindowMode.QUAD -> {
                val mx = area.left + area.width / 2
                val my = area.top + area.height / 2
                listOf(
                    IntRect(area.left, area.top, mx - h1, my - h1),
                    IntRect(mx + h2, area.top, area.right, my - h1),
                    IntRect(area.left, my + h2, mx - h1, area.bottom),
                    IntRect(mx + h2, my + h2, area.right, area.bottom),
                )
            }
            WindowMode.FREEFORM -> {
                val w = area.width * 7 / 10
                val h = area.height * 7 / 10
                val step = minOf(area.width - w, area.height - h) / 3
                (0..3).map { i ->
                    IntRect(area.left + step * i, area.top + step * i,
                        area.left + step * i + w, area.top + step * i + h)
                }
            }
        }
    }
}
