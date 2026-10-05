package com.healthsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SessionUploadRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val summary = PersonalSessionStore.pendingUpload(applicationContext)
            ?: return Result.success()

        return try {
            val sent = SessionBridge.send(applicationContext, summary)
            PersonalSessionStore.clearPendingUpload(applicationContext)
            HeartRateRetryWorker.schedule(
                applicationContext,
                sent.id,
                summary.startedAt,
                summary.endedAt,
            )
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "private-session-upload-retry"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SessionUploadRetryWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    10,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
