package com.example.myapplication.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages per-contact cloud AI consent preferences.
 *
 * Consent is stored individually for each contact phone number.
 * Granting consent for +91-9876543210 does NOT affect other contacts.
 * Each new suspicious contact triggers its own consent dialog.
 *
 * Storage format: SharedPreferences key = "cloud_consent_<sanitised_number>"
 *   - Key absent  → consent never asked (null)
 *   - Value true  → user granted consent
 *   - Value false → user declined
 */
class ConsentPreferenceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    /**
     * Returns the consent state for a contact number.
     * - `null`  = never asked → show consent dialog
     * - `true`  = user granted cloud AI consent
     * - `false` = user declined (use local only)
     */
    fun getConsent(contactNumber: String): Boolean? {
        val key = prefKey(contactNumber)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    /** Records the user's consent choice for a contact number. */
    fun setConsent(contactNumber: String, granted: Boolean) {
        prefs.edit().putBoolean(prefKey(contactNumber), granted).apply()
    }

    /** Returns all contact numbers that have been granted cloud consent. */
    fun getAllGrantedContacts(): List<String> =
        prefs.all
            .filter { it.key.startsWith(KEY_PREFIX) && it.value == true }
            .map { it.key.removePrefix(KEY_PREFIX) }

    /** Revokes cloud consent for a specific contact. */
    fun revokeConsent(contactNumber: String) {
        prefs.edit().remove(prefKey(contactNumber)).apply()
    }

    /** Revokes all stored cloud consents (can be triggered from a settings screen). */
    fun revokeAll() {
        prefs.edit()
            .apply { prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { remove(it) } }
            .apply()
    }

    private fun prefKey(contactNumber: String): String =
        KEY_PREFIX + contactNumber.replace(Regex("[^0-9+]"), "")

    companion object {
        private const val PREFS_NAME = "honeypot_cloud_consent"
        private const val KEY_PREFIX = "cloud_consent_"
    }
}
