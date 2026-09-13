package com.example.myapplication.filter

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists and provides access to each SMS pre-filter toggle.
 * Filters are enabled by default (conservative mode).
 */
class FilterPreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("filter_prefs", Context.MODE_PRIVATE)

    // ── Filter toggle keys ────────────────────────────────────────────────────

    /** Ignore messages from DLT-registered 6-char institutional senders (e.g. "AD-HDFCBK") */
    var filterDltInstitutional: Boolean
        get() = prefs.getBoolean(KEY_DLT, true)
        set(v) = prefs.edit().putBoolean(KEY_DLT, v).apply()

    /** Ignore messages that contain a 4-8 digit OTP verification pattern */
    var filterOtp: Boolean
        get() = prefs.getBoolean(KEY_OTP, true)
        set(v) = prefs.edit().putBoolean(KEY_OTP, v).apply()

    /** Ignore genuine bank transaction alerts (debit/credit with amount from DLT sender) */
    var filterBankTransaction: Boolean
        get() = prefs.getBoolean(KEY_BANK, true)
        set(v) = prefs.edit().putBoolean(KEY_BANK, v).apply()

    /** Ignore messages from senders the user has marked as safe */
    var filterSafeCache: Boolean
        get() = prefs.getBoolean(KEY_SAFE_CACHE, true)
        set(v) = prefs.edit().putBoolean(KEY_SAFE_CACHE, v).apply()

    companion object {
        private const val KEY_DLT         = "filter_dlt_institutional"
        private const val KEY_OTP         = "filter_otp"
        private const val KEY_BANK        = "filter_bank_transaction"
        private const val KEY_SAFE_CACHE  = "filter_safe_cache"
    }
}
