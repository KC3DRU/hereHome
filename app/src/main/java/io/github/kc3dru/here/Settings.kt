package io.github.kc3dru.here

import android.content.Context
import android.content.SharedPreferences

object Settings {
    private const val PREFS_NAME = "here_settings"
    private const val KEY_RECIPIENTS = "recipients"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get list of recipient phone numbers.
     * Stored as a comma-separated string (no spaces).
     * Each entry is passed through [PhoneNumber.normalize] so numbers saved by
     * older versions (e.g. "(212) 555-0100") are reported in E.164; entries
     * that cannot be normalised are returned unchanged.
     * Returns empty list if none configured.
     */
    fun getRecipients(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_RECIPIENTS, "") ?: ""
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { PhoneNumber.normalize(it) ?: it }
    }

    /**
     * Save list of recipient phone numbers.
     * @param recipients List of phone numbers (with country code)
     */
    fun saveRecipients(context: Context, recipients: List<String>) {
        val csv = recipients.joinToString(",")
        getPrefs(context).edit().putString(KEY_RECIPIENTS, csv).apply()
    }

    /**
     * Add a single recipient, stored in the E.164 form produced by
     * [PhoneNumber.normalize].
     * @return false if the input is not a usable number, or is already in the
     *   list — comparison is on the normalised value, so "(212) 555-0100" and
     *   "2125550100" cannot both be added.
     */
    fun addRecipient(context: Context, number: String): Boolean {
        val normalized = PhoneNumber.normalize(number) ?: return false
        val current = getRecipients(context).toMutableList()
        if (current.contains(normalized)) return false
        current.add(normalized)
        saveRecipients(context, current)
        return true
    }

    /**
     * Remove a recipient, matching on the normalised form of [number] so any
     * of the accepted input spellings deletes the entry.
     */
    fun removeRecipient(context: Context, number: String): Boolean {
        val normalized = PhoneNumber.normalize(number) ?: return false
        val current = getRecipients(context).toMutableList()
        return if (current.remove(normalized)) {
            saveRecipients(context, current)
            true
        } else false
    }
}
