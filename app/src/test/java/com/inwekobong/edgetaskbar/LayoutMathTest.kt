package com.inwekobong.edgetaskbar

import com.inwekobong.edgetaskbar.data.WindowMode
import com.inwekobong.edgetaskbar.windows.IntRect
import com.inwekobong.edgetaskbar.windows.LayoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutMathTest {
    private val portrait = IntRect(0, 100, 1000, 2100)
    private val landscape = IntRect(0, 100, 2000, 1000)

    private fun inside(r: IntRect, a: IntRect) =
        r.left >= a.left && r.top >= a.top && r.right <= a.right && r.bottom <= a.bottom && r.width > 0 && r.height > 0

    private fun overlap(a: IntRect, b: IntRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test fun fullIsWholeArea() {
        assertEquals(listOf(portrait), LayoutMath.slots(WindowMode.FULL, portrait))
    }

    @Test fun splitStacksInPortraitAndSidesInLandscape() {
        val p = LayoutMath.slots(WindowMode.SPLIT, portrait, 4)
        assertEquals(2, p.size)
        assertTrue(p[0].bottom <= p[1].top)
        val l = LayoutMath.slots(WindowMode.SPLIT, landscape, 4)
        assertTrue(l[0].right <= l[1].left)
        assertTrue((p + l).all { inside(it, if (it in p) portrait else landscape) })
    }

    @Test fun quadHasFourDisjointSlotsInsideArea() {
        val q = LayoutMath.slots(WindowMode.QUAD, portrait, 4)
        assertEquals(4, q.size)
        assertTrue(q.all { inside(it, portrait) })
        for (i in q.indices) for (j in i + 1 until q.size) assertTrue(!overlap(q[i], q[j]))
    }

    @Test fun freeformCascadeStaysInsideArea() {
        val f = LayoutMath.slots(WindowMode.FREEFORM, portrait)
        assertEquals(4, f.size)
        assertTrue(f.all { inside(it, portrait) })
    }
}
