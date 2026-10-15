package com.inwekobong.edgetaskbar.data

import android.graphics.drawable.Drawable

enum class DockEdge { TOP, BOTTOM, LEFT, RIGHT;
    val isHorizontal get() = this == TOP || this == BOTTOM
}

enum class DockAlign { START, CENTER, END }
enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

data class TaskbarSettings(
    val enabled: Boolean = false,
    val edge: DockEdge = DockEdge.BOTTOM,
    val align: DockAlign = DockAlign.CENTER,
    val iconSizeDp: Int = 44,
    val spacingDp: Int = 8,
    val paddingDp: Int = 10,
    val cornerRadiusDp: Int = 20,
    val handleLengthDp: Int = 56,
    val maxVisibleIcons: Int = 6,
    val opacityPercent: Int = 85,
    val theme: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val collapseAfterLaunch: Boolean = true,
    val autoCollapseSeconds: Int = 0, // 0 = never
    val hideOnKeyboard: Boolean = true,
    val startOnBoot: Boolean = false,
    val handleOffsetFraction: Float = 0.5f, // position along the edge, 0..1
    val temporarilyHidden: Boolean = false,
) {
    fun sanitized() = copy(
        iconSizeDp = iconSizeDp.coerceIn(24, 64),
        spacingDp = spacingDp.coerceIn(0, 24),
        paddingDp = paddingDp.coerceIn(4, 24),
        cornerRadiusDp = cornerRadiusDp.coerceIn(0, 32),
        handleLengthDp = handleLengthDp.coerceIn(36, 120),
        maxVisibleIcons = maxVisibleIcons.coerceIn(3, 10),
        opacityPercent = opacityPercent.coerceIn(30, 100),
        autoCollapseSeconds = autoCollapseSeconds.coerceIn(0, 60),
        handleOffsetFraction = handleOffsetFraction.coerceIn(0f, 1f),
    )
}

/** A launchable app discovered through PackageManager. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)
