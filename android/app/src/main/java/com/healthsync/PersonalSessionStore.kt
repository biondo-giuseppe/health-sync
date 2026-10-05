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

data class HeartRateSnapshot(
    val avg: Int?,
    val min: Int?,
    val max: Int?,
    val samples: Int,
)

data class HeartRateContext(
    val session: HeartRateSnapshot,
    val pre: HeartRateSnapshot,
    val post: HeartRateSnapshot,
)

data class PersonalSessionDraft(
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMinutes: Int,
    val heartRateAvg: Int?,
    val heartRateMin: Int?,
    val heartRateMax: Int?,
    val heartRateSamples: Int,
)

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
    private const val KEY_PENDING_DRAFT = "pending_draft"
    private const val KEY_LAST_SUMMARY = "last_summary"
    private const val KEY_PENDING_UPLOAD = "pending_upload"

    fun activeStart(context: Context): Instant? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_START, null) ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    fun start(context: Context): Instant {
        clearPendingDraft(context)
        val now = Instant.now()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_START, now.toString())
            .apply()
        return now
    }

    suspend fun stopForFeedback(context: Context): PersonalSessionDraft {
        val start = activeStart(context) ?: error("No active session")
        val end = Instant.now()
        val hr = readHeartRate(context, start, end)
        val draft = PersonalSessionDraft(
            startedAt = start,
            endedAt = end,
            durationMinutes = Duration.between(start, end).toMinutes().coerceAtLeast(1).toInt(),
            heartRateAvg = hr.avg,
            heartRateMin = hr.min,
            heartRateMax = hr.max,
            heartRateSamples = hr.samples,
        )
        saveDraft(context, draft)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_START)
            .apply()
        return draft
    }

    fun pendingDraft(context: Context): PersonalSessionDraft? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_DRAFT, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            PersonalSessionDraft(
                startedAt = Instant.parse(j.getString("started_at")),
                endedAt = Instant.parse(j.getString("ended_at")),
                durationMinutes = j.getInt("duration_minutes"),
                heartRateAvg = j.optIntOrNull("hr_avg"),
                heartRateMin = j.optIntOrNull("hr_min"),
                heartRateMax = j.optIntOrNull("hr_max"),
                heartRateSamples = j.optInt("hr_samples", 0),
            )
        }.getOrNull()
    }

    fun completeDraft(
        context: Context,
        intensity: Int,
        control: Int,
        energy: Int,
        wellbeing: Int,
    ): PersonalSessionSummary {
        val draft = pendingDraft(context) ?: error("No session awaiting feedback")
        val status = when {
            wellbeing <= 2 || energy <= 2 || control <= 2 -> "ATTENZIONE"
            intensity >= 4 && control >= 4 && wellbeing >= 4 -> "MIGLIORA"
            else -> "STABILE"
        }
        val summary = PersonalSessionSummary(
            startedAt = draft.startedAt,
            endedAt = draft.endedAt,
            durationMinutes = draft.durationMinutes,
            heartRateAvg = draft.heartRateAvg,
            heartRateMin = draft.heartRateMin,
            heartRateMax = draft.heartRateMax,
            heartRateSamples = draft.heartRateSamples,
            intensity = intensity,
            control = control,
            energy = energy,
            wellbeing = wellbeing,
            status = status,
        )
        saveSummary(context, summary)
        savePendingUpload(context, summary)
        clearPendingDraft(context)
        return summary
    }

    fun clearPendingDraft(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_DRAFT).apply()
    }

    fun lastSummary(context: Context): PersonalSessionSummary? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SUMMARY, null) ?: return null
        return parseSummary(raw)
    }

    fun pendingUpload(context: Context): PersonalSessionSummary? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_UPLOAD, null) ?: return null
        return parseSummary(raw)
    }

    fun clearPendingUpload(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_UPLOAD).apply()
    }

    fun markPendingUpload(context: Context, s: PersonalSessionSummary) {
        savePendingUpload(context, s)
    }

    fun retryCandidate(context: Context): PersonalSessionSummary? {
        pendingUpload(context)?.let { return it }
        val last = lastSummary(context) ?: return null
        val ageMinutes = Duration.between(last.endedAt, Instant.now()).toMinutes()
        return last.takeIf { ageMinutes in 0..1440 }
    }

    private fun savePendingUpload(context: Context, s: PersonalSessionSummary) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_UPLOAD, summaryJson(s)).apply()
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

    suspend fun readHeartRate(
        context: Context,
        start: Instant,
        end: Instant,
    ): HeartRateSnapshot = readHeartRateWindow(context, start, end)

    suspend fun readHeartRateContext(
        context: Context,
        start: Instant,
        end: Instant,
    ): HeartRateContext {
        val marginSeconds = 120L
        return HeartRateContext(
            session = readHeartRateWindow(context, start, end),
            pre = readHeartRateWindow(context, start.minusSeconds(marginSeconds), start),
            post = readHeartRateWindow(context, end, end.plusSeconds(marginSeconds)),
        )
    }

    private suspend fun readHeartRateWindow(
        context: Context,
        start: Instant,
        end: Instant,
    ): HeartRateSnapshot {
        val client = HealthConnectClient.getOrCreate(context)
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
            )
        ).records
        val values = records.flatMap { record ->
            record.samples
                .filter { sample -> !sample.time.isBefore(start) && sample.time.isBefore(end) }
                .map { it.beatsPerMinute.toInt() }
        }
        if (values.isEmpty()) return HeartRateSnapshot(null, null, null, 0)
        return HeartRateSnapshot(
            avg = values.average().roundToInt(),
            min = values.minOrNull(),
            max = values.maxOrNull(),
            samples = values.size,
        )
    }

    private fun saveDraft(context: Context, d: PersonalSessionDraft) {
        val json = JSONObject().apply {
            put("started_at", d.startedAt.toString())
            put("ended_at", d.endedAt.toString())
            put("duration_minutes", d.durationMinutes)
            d.heartRateAvg?.let { put("hr_avg", it) }
            d.heartRateMin?.let { put("hr_min", it) }
            d.heartRateMax?.let { put("hr_max", it) }
            put("hr_samples", d.heartRateSamples)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_DRAFT, json.toString()).apply()
    }

    private fun saveSummary(context: Context, s: PersonalSessionSummary) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_SUMMARY, summaryJson(s)).apply()
    }

    private fun summaryJson(s: PersonalSessionSummary): String = JSONObject().apply {
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
    }.toString()

    private fun parseSummary(raw: String): PersonalSessionSummary? = runCatching {
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

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null
}
