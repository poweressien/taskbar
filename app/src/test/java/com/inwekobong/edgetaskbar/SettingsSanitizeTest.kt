package com.inwekobong.edgetaskbar

import com.inwekobong.edgetaskbar.data.DockAlign
import com.inwekobong.edgetaskbar.data.DockEdge
import com.inwekobong.edgetaskbar.data.TaskbarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSanitizeTest {

    @Test fun clampsOutOfRangeValues() {
        val s = TaskbarSettings(
            iconSizeDp = 500, spacingDp = -4, opacityPercent = 0, maxVisibleIcons = 99,
            handleLengthDp = 1, handleOffsetFraction = 9f, autoCollapseSeconds = 999,
            paddingDp = 0, cornerRadiusDp = 99,
        ).sanitized()
        assertEquals(64, s.iconSizeDp)
        assertEquals(0, s.spacingDp)
        assertEquals(30, s.opacityPercent) // never fully invisible
        assertEquals(10, s.maxVisibleIcons)
        assertEquals(36, s.handleLengthDp)
        assertEquals(1f, s.handleOffsetFraction, 0f)
        assertEquals(60, s.autoCollapseSeconds)
        assertEquals(4, s.paddingDp)
        assertEquals(32, s.cornerRadiusDp)
    }

    @Test fun defaultsAreValidAndBootDisabled() {
        val s = TaskbarSettings()
        assertEquals(44, s.iconSizeDp)
        assertFalse(s.startOnBoot)
        assertEquals(s, s.sanitized())
    }

    @Test fun pinnedBarAlwaysBottomCentered() {
        val s = TaskbarSettings(pinnedBar = true, edge = DockEdge.LEFT, align = DockAlign.END).effective()
        assertEquals(DockEdge.BOTTOM, s.edge)
        assertEquals(DockAlign.CENTER, s.align)
        val f = TaskbarSettings(pinnedBar = false, edge = DockEdge.LEFT).effective()
        assertEquals(DockEdge.LEFT, f.edge)
    }

    @Test fun edgeOrientation() {
        assertTrue(DockEdge.TOP.isHorizontal)
        assertTrue(DockEdge.BOTTOM.isHorizontal)
        assertFalse(DockEdge.LEFT.isHorizontal)
        assertFalse(DockEdge.RIGHT.isHorizontal)
    }
}
