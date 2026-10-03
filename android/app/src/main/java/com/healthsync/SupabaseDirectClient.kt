package com.healthsync

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SupabaseDirectClient {
    private const val ENDPOINT = "https://kmxwmoagqwmitaripxrp.supabase.co/functions/v1/health-sync-direct"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    fun isPaired(context: Context): Boolean = SecureTokenStore.hasToken(context)

    fun pair(context: Context, code: String, deviceId: String) {
        val body = JSONObject()
            .put("action", "pair")
            .put("code", code.trim())
            .put("device_id", deviceId)
            .toString()
            .toRequestBody(jsonType)

        client.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Pairing failed (" + response.code + ")")
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) throw IllegalStateException(json.optString("error", "Pairing failed"))
            val token = json.optString("token")
            require(token.isNotBlank()) { "Pairing response did not include a token" }
            SecureTokenStore.saveToken(context, token)
        }
    }

    fun prepareAndCache(context: Context, snapshot: HealthSnapshot) {
        val payload = DriveClient.snapshotToJsonObject(snapshot, includeRawRecords = true).toString()
        PendingSupabaseCache.save(context, payload)
    }

    fun flushPending(context: Context): String? {
        val payload = PendingSupabaseCache.read(context) ?: return null
        val token = SecureTokenStore.getToken(context) ?: throw IllegalStateException("Supabase is not paired")

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-health-sync-key", token)
            .post(payload.toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Supabase sync failed (" + response.code + ")")
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) throw IllegalStateException(json.optString("error", "Supabase sync failed"))
            return json.optString("source_recorded_at").takeIf { it.isNotBlank() }
        }
    }

    fun syncSnapshot(context: Context, snapshot: HealthSnapshot) {
        prepareAndCache(context, snapshot)
        flushPending(context)
        PendingSupabaseCache.clear(context)
    }
}
