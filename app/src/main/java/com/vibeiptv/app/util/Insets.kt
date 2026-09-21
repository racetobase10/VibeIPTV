package com.vibeiptv.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads a view by the system-bar insets (status bar, navigation bar, cutout)
 * so content no longer draws underneath them on edge-to-edge displays
 * (Android 15 enforces edge-to-edge). Each side can be opted out when the
 * view should stay full-bleed on that edge (e.g. video surfaces).
 */
fun View.applySystemBarPadding(
    left: Boolean = true,
    top: Boolean = true,
    right: Boolean = true,
    bottom: Boolean = true
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            if (left) bars.left else v.paddingLeft,
            if (top) bars.top else v.paddingTop,
            if (right) bars.right else v.paddingRight,
            if (bottom) bars.bottom else v.paddingBottom
        )
        insets
    }
}
