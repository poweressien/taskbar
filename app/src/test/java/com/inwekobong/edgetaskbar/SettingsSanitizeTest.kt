package com.inwekobong.edgetaskbar

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
            handleLengthDp = 1, handleOffsetFraction = 9f
        ).sanitized()
        assertEquals(64, s.iconSizeDp)
        assertEquals(0, s.spacingDp)
        assertEquals(30, s.opacityPercent) // never fully invisible
        assertEquals(10, s.maxVisibleIcons)
        assertEquals(36, s.handleLengthDp)
        assertEquals(1f, s.handleOffsetFraction, 0f)
    }

    @Test fun defaultsAreValidAndBootDisabled() {
        val s = TaskbarSettings()
        assertEquals(44, s.iconSizeDp)
        assertFalse(s.startOnBoot)
        assertEquals(s, s.sanitized())
    }

    @Test fun edgeOrientation() {
        assertTrue(DockEdge.TOP.isHorizontal)
        assertTrue(DockEdge.BOTTOM.isHorizontal)
        assertFalse(DockEdge.LEFT.isHorizontal)
        assertFalse(DockEdge.RIGHT.isHorizontal)
    }
}
