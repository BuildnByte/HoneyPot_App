package com.honeypot.app.manualcapture

import android.content.Context
import androidx.core.content.edit

/**
 * Lightweight SharedPreferences wrapper for manual-capture feature state.
 *
 * Intentionally kept small — no sensitive content is stored here.
 * All analysis results live in the Room database.
 */
class ManualCapturePrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True while the scan overlay is intended to be showing. */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_OVERLAY_ENABLED, value) }

    /**
     * ID of the most recently completed analysis that has not yet been viewed
     * via ManualAnalysisActivity. Cleared when the user opens the result.
     */
    var unviewedCaptureId: String?
        get() = prefs.getString(KEY_UNVIEWED_CAPTURE_ID, null)
        set(value) = prefs.edit { putString(KEY_UNVIEWED_CAPTURE_ID, value) }

    /** True when an analysis is currently running — used to show the "Analysing…" state. */
    var isAnalysing: Boolean
        get() = prefs.getBoolean(KEY_IS_ANALYSING, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_ANALYSING, value) }

    /**
     * Layout hint: "RIGHT_IS_MINE" (default) or "LEFT_IS_MINE".
     * Survives across capture sessions so the user only sets it once.
     */
    var senderLayout: String
        get() = prefs.getString(KEY_SENDER_LAYOUT, DEFAULT_LAYOUT) ?: DEFAULT_LAYOUT
        set(value) = prefs.edit { putString(KEY_SENDER_LAYOUT, value) }

    /**
     * Allowed apps mode: [MODE_ALL_APPS] (default) or [MODE_SELECTED_APPS].
     */
    var allowedAppsMode: String
        get() = prefs.getString(KEY_ALLOWED_APPS_MODE, MODE_ALL_APPS) ?: MODE_ALL_APPS
        set(value) = prefs.edit { putString(KEY_ALLOWED_APPS_MODE, value) }

    /**
     * Set of package names permitted for screen capture when [allowedAppsMode] is [MODE_SELECTED_APPS].
     */
    var allowedAppPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_APP_PACKAGES, DEFAULT_ALLOWED_PACKAGES) ?: DEFAULT_ALLOWED_PACKAGES
        set(value) = prefs.edit { putStringSet(KEY_ALLOWED_APP_PACKAGES, value) }

    /**
     * True once the user has configured and confirmed their app selection preference.
     */
    var hasConfiguredAppSelection: Boolean
        get() = prefs.getBoolean(KEY_HAS_CONFIGURED_APP_SELECTION, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_CONFIGURED_APP_SELECTION, value) }

    fun clearResultState() {
        prefs.edit {
            remove(KEY_UNVIEWED_CAPTURE_ID)
            putBoolean(KEY_IS_ANALYSING, false)
        }
    }

    companion object {
        private const val PREFS_NAME = "manual_capture_prefs"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_UNVIEWED_CAPTURE_ID = "unviewed_capture_id"
        private const val KEY_IS_ANALYSING = "is_analysing"
        private const val KEY_SENDER_LAYOUT = "sender_layout"
        private const val KEY_ALLOWED_APPS_MODE = "allowed_apps_mode"
        private const val KEY_ALLOWED_APP_PACKAGES = "allowed_app_packages"
        private const val KEY_HAS_CONFIGURED_APP_SELECTION = "has_configured_app_selection"

        const val DEFAULT_LAYOUT = "RIGHT_IS_MINE"
        const val LAYOUT_LEFT_IS_MINE = "LEFT_IS_MINE"

        const val MODE_ALL_APPS = "ALL_APPS"
        const val MODE_SELECTED_APPS = "SELECTED_APPS"

        const val PKG_WHATSAPP = "com.whatsapp"
        const val PKG_TELEGRAM = "org.telegram.messenger"
        const val PKG_INSTAGRAM = "com.instagram.android"

        val DEFAULT_ALLOWED_PACKAGES = setOf(PKG_WHATSAPP, PKG_TELEGRAM, PKG_INSTAGRAM)
    }
}
