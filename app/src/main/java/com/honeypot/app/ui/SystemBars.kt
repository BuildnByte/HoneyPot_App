package com.honeypot.app.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps app content clear of the status bar/camera cutout and navigation bar. */
object SystemBars {
    fun apply(activity: Activity, content: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val initialTop = content.paddingTop
        val initialBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            view.setPadding(view.paddingLeft, initialTop + bars.top, view.paddingRight, initialBottom + bottomInset)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }
}
