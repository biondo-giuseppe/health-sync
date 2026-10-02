package com.healthsync

import android.content.Context

object AutoSyncPrefs {
    private const val PREFS_NAME = "health_sync_prefs"
    private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SYNC_ENABLED, enabled)
            .apply()
    }
}
