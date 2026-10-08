package com.healthsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.util.concurrent.TimeUnit

class HeartRateRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val start = inputData.getString(KEY_START)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return Result.failure()
        val end = inputData.getString(KEY_END)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return Result.failure()

        return try {
            val contextHr = PersonalSessionStore.readHeartRateContext(applicationContext, start, end)
            val update = SessionBridge.sendHeartRateUpdate(applicationContext, sessionId, contextHr)

            when {
                !update.pending -> Result.success()
                update.exhausted -> Result.success()
                runAttemptCount >= 5 -> Result.success()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_START = "started_at"
        private const val KEY_END = "ended_at"

        fun schedule(context: Context, sessionId: String, start: Instant, end: Instant) {
            val data = Data.Builder()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_START, start.toString())
                .putString(KEY_END, end.toString())
                .build()

            val request = OneTimeWorkRequestBuilder<HeartRateRetryWorker>()
                .setInputData(data)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    10,
                    TimeUnit.MINUTES,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "private-session-hr-$sessionId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
