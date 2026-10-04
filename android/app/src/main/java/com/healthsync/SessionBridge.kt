package com.healthsync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

data class SessionSendResult(
    val id: String,
    val status: String,
)

object SessionBridge {
    private const val PREFS = "session_bridge"
    private const val KEY_LINK = "link_value"
    private const val KEY_DEVICE = "device_id"
    private const val PAIR_URL = "https://kmxwmoagqwmitaripxrp.supabase.co/functions/v1/health-sync-direct"
    private const val SAVE_URL = "https://kmxwmoagqwmitaripxrp.supabase.co/functions/v1/private-session-ingest"
    private const val HR_URL = "https://kmxwmoagqwmitaripxrp.supabase.co/functions/v1/private-session-heart-rate"
    private val http = OkHttpClient()

    fun isLinked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_LINK)

    private fun linkValue(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LINK, null)

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    suspend fun link(context: Context, code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("^\\d{6}$"))) { "Codice non valido" }
        val payload = JSONObject()
            .put("action", "pair")
            .put("code", code)
            .put("device_id", deviceId(context))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val response = http.newCall(Request.Builder().url(PAIR_URL).post(payload).build()).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!it.isSuccessful || json?.optBoolean("ok") != true) {
                error(json?.optString("error")?.ifBlank { "Collegamento non riuscito" } ?: "Collegamento non riuscito")
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LINK, json.getString("token")).apply()
        }
    }

    suspend fun send(context: Context, s: PersonalSessionSummary): SessionSendResult = withContext(Dispatchers.IO) {
        val link = linkValue(context) ?: error("Collega prima il dispositivo")
        val json = JSONObject()
            .put("started_at", s.startedAt.toString())
            .put("ended_at", s.endedAt.toString())
            .put("intensity", s.intensity)
            .put("control", s.control)
            .put("energy", s.energy)
            .put("wellbeing", s.wellbeing)
            .put("hr_samples", s.heartRateSamples)
        s.heartRateAvg?.let { json.put("hr_avg_bpm", it) }
        s.heartRateMin?.let { json.put("hr_min_bpm", it) }
        s.heartRateMax?.let { json.put("hr_max_bpm", it) }

        val request = Request.Builder()
            .url(SAVE_URL)
            .addHeader("x-health-sync-key", link)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrNull()
            if (!it.isSuccessful || result?.optBoolean("ok") != true) {
                error(result?.optString("error")?.ifBlank { "Invio non riuscito" } ?: "Invio non riuscito")
            }
            SessionSendResult(
                id = result.getString("id"),
                status = result.optString("status", "stable"),
            )
        }
    }

    suspend fun sendHeartRateUpdate(
        context: Context,
        sessionId: String,
        heartRate: HeartRateSnapshot,
    ) = withContext(Dispatchers.IO) {
        val link = linkValue(context) ?: error("Collega prima il dispositivo")
        require(heartRate.avg != null && heartRate.samples > 0) { "Nessun dato cardiaco disponibile" }

        val json = JSONObject()
            .put("session_id", sessionId)
            .put("hr_avg_bpm", heartRate.avg)
            .put("hr_samples", heartRate.samples)
        heartRate.min?.let { json.put("hr_min_bpm", it) }
        heartRate.max?.let { json.put("hr_max_bpm", it) }

        val request = Request.Builder()
            .url(HR_URL)
            .addHeader("x-health-sync-key", link)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrNull()
            if (!it.isSuccessful || result?.optBoolean("ok") != true) {
                error(result?.optString("error")?.ifBlank { "Aggiornamento battito non riuscito" }
                    ?: "Aggiornamento battito non riuscito")
            }
        }
    }
}
