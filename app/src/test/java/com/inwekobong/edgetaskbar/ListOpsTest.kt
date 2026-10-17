package com.inwekobong.edgetaskbar

import com.inwekobong.edgetaskbar.data.ListOps
import org.junit.Assert.assertEquals
import org.junit.Test

class ListOpsTest {
    @Test fun encodeDecodeRoundTripDropsBlanksAndDuplicates() {
        assertEquals(listOf("a", "b"), ListOps.decode(" a\n\nb\na "))
        assertEquals("a\nb", ListOps.encode(listOf("a", "b", "a")))
        assertEquals(emptyList<String>(), ListOps.decode(null))
    }

    @Test fun recentsNewestFirstNoDuplicatesBounded() {
        assertEquals(listOf("c", "a", "b"), ListOps.addRecent(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("a", "b"), ListOps.addRecent(listOf("a", "b"), "a"))
        assertEquals(3, ListOps.addRecent(listOf("a", "b", "c"), "d", limit = 3).size)
        assertEquals("d", ListOps.addRecent(listOf("a", "b", "c"), "d", limit = 3).first())
    }

    @Test fun pinUnpinInsert() {
        assertEquals(listOf("a", "b"), ListOps.pin(listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b", "c"), ListOps.pin(listOf("a", "b"), "c"))
        assertEquals(listOf("a"), ListOps.unpin(listOf("a", "b"), "b"))
        assertEquals(listOf("a", "x", "b"), ListOps.insert(listOf("a", "b"), 1, "x"))
        assertEquals(listOf("a", "b", "x"), ListOps.insert(listOf("a", "b"), 99, "x"))
    }

    @Test fun moveReorders() {
        assertEquals(listOf("b", "c", "a"), ListOps.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("a", "b"), ListOps.move(listOf("a", "b"), 0, 5))
    }

    @Test fun keepValidAndFilterAndPages() {
        assertEquals(listOf("a", "c"), ListOps.keepValid(listOf("a", "b", "c"), setOf("a", "c")))
        assertEquals(listOf("Chrome"), ListOps.filterByLabel(listOf("Chrome", "Maps"), " chr ".trim()) { it })
        assertEquals(2, ListOps.filterByLabel(listOf("Chrome", "Maps"), "") { it }.size)
        assertEquals(1, ListOps.pageCount(0, 6))
        assertEquals(1, ListOps.pageCount(6, 6))
        assertEquals(2, ListOps.pageCount(7, 6))
    }
}
