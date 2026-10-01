package com.healthsync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONObject

object DriveWriteVerifier {
    private const val PREFS = "health_sync"
    private const val KEY_FILE_URI = "drive_file_uri"

    suspend fun awaitRecordedAt(
        context: Context,
        expectedRecordedAt: String,
        attempts: Int = 4,
        delayMs: Long = 1500L,
    ) {
        val uriText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FILE_URI, null)
            ?: throw IllegalStateException("Google Drive file URI is missing")
        val uri = Uri.parse(uriText)

        repeat(attempts) { index ->
            val actual = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val root = JSONObject(input.bufferedReader().readText())
                    root.optJSONObject("latest_full_export")?.optString("recorded_at")
                }
            }.getOrNull()

            if (actual == expectedRecordedAt) return
            if (index < attempts - 1) delay(delayMs)
        }

        throw IllegalStateException("Drive write could not be verified")
    }
}
