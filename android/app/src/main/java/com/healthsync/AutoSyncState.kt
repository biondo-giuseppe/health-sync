package com.healthsync

import android.content.Context
import java.time.Duration
import java.time.ZonedDateTime

object AutoSyncState {
    private const val PREFS = "health_sync_settings"
    private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
    private const val KEY_INTERVAL_MINUTES = "auto_sync_interval_minutes"
    private const val KEY_LAST_SUCCESS = "last_sync_success"
    private const val KEY_LAST_ERROR = "last_sync_error"
    private const val KEY_LAST_ATTEMPT = "last_sync_attempt"
    private const val KEY_LAST_STAGE = "last_sync_stage"

    const val DEFAULT_INTERVAL_MINUTES = 15L
    val ALLOWED_INTERVALS = setOf(15L, 30L, 60L, 480L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SYNC_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
    }

    fun intervalMinutes(context: Context): Long {
        val value = prefs(context).getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        return if (value in ALLOWED_INTERVALS) value else DEFAULT_INTERVAL_MINUTES
    }

    fun setIntervalMinutes(context: Context, minutes: Long) {
        require(minutes in ALLOWED_INTERVALS) { "Unsupported auto sync interval" }
        prefs(context).edit().putLong(KEY_INTERVAL_MINUTES, minutes).apply()
    }

    fun recordAttempt(context: Context, timestamp: String, stage: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ATTEMPT, timestamp)
            .putString(KEY_LAST_STAGE, stage.take(80))
            .apply()
    }

    fun recordSuccess(context: Context, timestamp: String) {
        prefs(context).edit()
            .putString(KEY_LAST_SUCCESS, timestamp)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(context: Context, message: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ERROR, message.take(160))
            .apply()
    }

    fun lastSuccess(context: Context): String? = prefs(context).getString(KEY_LAST_SUCCESS, null)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    fun lastAttempt(context: Context): String? = prefs(context).getString(KEY_LAST_ATTEMPT, null)

    fun lastStage(context: Context): String? = prefs(context).getString(KEY_LAST_STAGE, null)

    fun minutesSinceLastSuccess(context: Context): Long? {
        val value = lastSuccess(context) ?: return null
        return runCatching {
            val then = ZonedDateTime.parse(value)
            Duration.between(then, ZonedDateTime.now()).toMinutes().coerceAtLeast(0)
        }.getOrNull()
    }

    fun staleThresholdMinutes(context: Context): Long =
        maxOf(45L, intervalMinutes(context) * 3L)

    fun isStale(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val age = minutesSinceLastSuccess(context) ?: return true
        return age >= staleThresholdMinutes(context)
    }
}
