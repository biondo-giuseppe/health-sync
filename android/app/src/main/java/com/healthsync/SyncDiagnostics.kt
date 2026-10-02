package com.healthsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

object SyncDiagnostics {
    private const val PREFS = "health_sync_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 30

    data class Handle(
        val id: String,
        val trigger: String,
        val attempt: Int,
        val startedAt: String,
    )

    fun start(context: Context, trigger: String, attempt: Int): Handle {
        val handle = Handle(
            id = "\${System.currentTimeMillis()}-\${trigger}-\${attempt}",
            trigger = trigger,
            attempt = attempt,
            startedAt = ZonedDateTime.now().toString(),
        )
        append(context, JSONObject().apply {
            put("id", handle.id)
            put("trigger", trigger)
            put("attempt", attempt)
            put("started_at", handle.startedAt)
            put("phase", "started")
            put("result", "running")
            put("network", networkState(context))
            put("battery_optimization_ignored", batteryOptimizationIgnored(context))
        })
        return handle
    }

    fun phase(context: Context, handle: Handle, phase: String) {
        update(context, handle.id) {
            put("phase", phase)
            put("phase_at", ZonedDateTime.now().toString())
        }
    }

    fun success(
        context: Context,
        handle: Handle,
        recordedAt: String,
        steps: Long?,
        zeppStepsLastModifiedAt: String?,
    ) {
        update(context, handle.id) {
            put("ended_at", ZonedDateTime.now().toString())
            put("phase", "completed")
            put("result", "success")
            put("snapshot_recorded_at", recordedAt)
            steps?.let { put("steps", it) }
            zeppStepsLastModifiedAt?.let { put("zepp_steps_last_modified_at", it) }
        }
    }

    fun failure(context: Context, handle: Handle, phase: String, error: Throwable, retry: Boolean) {
        update(context, handle.id) {
            put("ended_at", ZonedDateTime.now().toString())
            put("phase", phase)
            put("result", if (retry) "retry" else "error")
            put("error_type", error.javaClass.simpleName)
            put("error", (error.message ?: error.javaClass.simpleName).take(240))
        }
    }

    fun permanent(context: Context, handle: Handle, phase: String, message: String) {
        update(context, handle.id) {
            put("ended_at", ZonedDateTime.now().toString())
            put("phase", phase)
            put("result", "blocked")
            put("error", message.take(240))
        }
    }

    fun summary(context: Context, limit: Int = 6): String {
        val array = load(context)
        if (array.length() == 0) return "Nessun tentativo registrato."
        val lines = mutableListOf<String>()
        val start = (array.length() - limit).coerceAtLeast(0)
        for (i in array.length() - 1 downTo start) {
            val e = array.optJSONObject(i) ?: continue
            val started = e.optString("started_at").replace(Regex("\\[[^]]+]$"), "")
            val shortTime = runCatching {
                val zdt = ZonedDateTime.parse(started)
                "%02d:%02d".format(zdt.hour, zdt.minute)
            }.getOrDefault(started.takeLast(8))
            lines += buildString {
                append(shortTime)
                append(" · ")
                append(e.optString("trigger", "?"))
                append(" · ")
                append(e.optString("result", "?"))
                append(" · ")
                append(e.optString("phase", "?"))
                if (e.has("error")) append(" · \${e.optString("error")}")
            }
        }
        return lines.joinToString("\n")
    }

    private fun append(context: Context, event: JSONObject) {
        val array = load(context)
        array.put(event)
        trimAndSave(context, array)
    }

    private fun update(context: Context, id: String, block: JSONObject.() -> Unit) {
        val array = load(context)
        for (i in 0 until array.length()) {
            val event = array.optJSONObject(i) ?: continue
            if (event.optString("id") == id) {
                event.block()
                break
            }
        }
        trimAndSave(context, array)
    }

    private fun load(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null)
            ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun trimAndSave(context: Context, source: JSONArray) {
        val trimmed = JSONArray()
        val start = (source.length() - MAX_EVENTS).coerceAtLeast(0)
        for (i in start until source.length()) trimmed.put(source.get(i))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, trimmed.toString())
            .apply()
    }

    private fun networkState(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val network = cm.activeNetwork ?: return "disconnected"
        val caps = cm.getNetworkCapabilities(network) ?: return "disconnected"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "connected"
        }
    }

    private fun batteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
