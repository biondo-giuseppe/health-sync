package com.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.roundToInt

data class PersonalSessionSummary(
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMinutes: Int,
    val heartRateAvg: Int?,
    val heartRateMin: Int?,
    val heartRateMax: Int?,
    val heartRateSamples: Int,
    val intensity: Int,
    val control: Int,
    val energy: Int,
    val wellbeing: Int,
    val status: String,
)

object PersonalSessionStore {
    private const val PREFS = "personal_session"
    private const val KEY_ACTIVE_START = "active_start"
    private const val KEY_LAST_SUMMARY = "last_summary"

    fun activeStart(context: Context): Instant? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_START, null) ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    fun start(context: Context): Instant {
        val now = Instant.now()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_START, now.toString())
            .apply()
        return now
    }

    fun clearActive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_START)
            .apply()
    }

    suspend fun finish(
        context: Context,
        intensity: Int,
        control: Int,
        energy: Int,
        wellbeing: Int,
    ): PersonalSessionSummary {
        val start = activeStart(context) ?: error("No active session")
        val end = Instant.now()
        val hr = readHeartRate(context, start, end)
        val duration = Duration.between(start, end).toMinutes().coerceAtLeast(1).toInt()

        val status = when {
            wellbeing <= 2 || energy <= 2 || control <= 2 -> "ATTENZIONE"
            intensity >= 4 && control >= 4 && wellbeing >= 4 -> "MIGLIORA"
            else -> "STABILE"
        }

        val summary = PersonalSessionSummary(
            startedAt = start,
            endedAt = end,
            durationMinutes = duration,
            heartRateAvg = hr.avg,
            heartRateMin = hr.min,
            heartRateMax = hr.max,
            heartRateSamples = hr.samples,
            intensity = intensity,
            control = control,
            energy = energy,
            wellbeing = wellbeing,
            status = status,
        )
        saveSummary(context, summary)
        clearActive(context)
        return summary
    }

    fun lastSummary(context: Context): PersonalSessionSummary? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SUMMARY, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            PersonalSessionSummary(
                startedAt = Instant.parse(j.getString("started_at")),
                endedAt = Instant.parse(j.getString("ended_at")),
                durationMinutes = j.getInt("duration_minutes"),
                heartRateAvg = j.optIntOrNull("hr_avg"),
                heartRateMin = j.optIntOrNull("hr_min"),
                heartRateMax = j.optIntOrNull("hr_max"),
                heartRateSamples = j.optInt("hr_samples", 0),
                intensity = j.optInt("intensity", 3),
                control = j.optInt("control", 3),
                energy = j.optInt("energy", 3),
                wellbeing = j.optInt("wellbeing", 3),
                status = j.optString("status", "STABILE"),
            )
        }.getOrNull()
    }

    fun shareText(summary: PersonalSessionSummary): String = buildString {
        appendLine("GIUSEPPE_SESSION")
        appendLine("start=${ZonedDateTime.ofInstant(summary.startedAt, java.time.ZoneId.systemDefault())}")
        appendLine("end=${ZonedDateTime.ofInstant(summary.endedAt, java.time.ZoneId.systemDefault())}")
        appendLine("duration_min=${summary.durationMinutes}")
        appendLine("hr_avg=${summary.heartRateAvg ?: "na"}")
        appendLine("hr_min=${summary.heartRateMin ?: "na"}")
        appendLine("hr_max=${summary.heartRateMax ?: "na"}")
        appendLine("hr_samples=${summary.heartRateSamples}")
        appendLine("intensity=${summary.intensity}/5")
        appendLine("control=${summary.control}/5")
        appendLine("energy=${summary.energy}/5")
        appendLine("wellbeing=${summary.wellbeing}/5")
        appendLine("local_status=${summary.status}")
    }

    private fun saveSummary(context: Context, s: PersonalSessionSummary) {
        val json = JSONObject().apply {
            put("started_at", s.startedAt.toString())
            put("ended_at", s.endedAt.toString())
            put("duration_minutes", s.durationMinutes)
            s.heartRateAvg?.let { put("hr_avg", it) }
            s.heartRateMin?.let { put("hr_min", it) }
            s.heartRateMax?.let { put("hr_max", it) }
            put("hr_samples", s.heartRateSamples)
            put("intensity", s.intensity)
            put("control", s.control)
            put("energy", s.energy)
            put("wellbeing", s.wellbeing)
            put("status", s.status)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SUMMARY, json.toString())
            .apply()
    }

    private data class HrStats(val avg: Int?, val min: Int?, val max: Int?, val samples: Int)

    private suspend fun readHeartRate(context: Context, start: Instant, end: Instant): HrStats {
        val client = HealthConnectClient.getOrCreate(context)
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
            )
        ).records
        val values = records.flatMap { record -> record.samples.map { it.beatsPerMinute.toInt() } }
        if (values.isEmpty()) return HrStats(null, null, null, 0)
        return HrStats(
            avg = values.average().roundToInt(),
            min = values.minOrNull(),
            max = values.maxOrNull(),
            samples = values.size,
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null
}
