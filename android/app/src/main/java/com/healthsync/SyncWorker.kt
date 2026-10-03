package com.healthsync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)

        return try {
            AutoSyncState.recordAttempt(applicationContext, ZonedDateTime.now().toString(), "worker_started")

            val manager = PreferredHealthConnectManager(applicationContext)

            if (!manager.hasPermissions()) {
                AutoSyncState.recordError(applicationContext, "Health Connect permissions missing")
                return if (oneShot) Result.failure() else Result.success()
            }

            AutoSyncState.recordAttempt(applicationContext, ZonedDateTime.now().toString(), "health_read_started")
            val snapshot = manager.readTodaySnapshot()
            val compactSnapshot = SyncPayload.compactForBackground(snapshot)

            AutoSyncState.recordAttempt(applicationContext, ZonedDateTime.now().toString(), "drive_write_started")
            withContext(Dispatchers.IO) {
                DriveClient.syncSnapshot(applicationContext, compactSnapshot)
            }

            AutoSyncState.recordAttempt(applicationContext, ZonedDateTime.now().toString(), "drive_verify_started")
            DriveWriteVerifier.awaitRecordedAt(
                applicationContext,
                compactSnapshot.recordedAt
            )

            AutoSyncState.recordAttempt(applicationContext, ZonedDateTime.now().toString(), "completed")
            AutoSyncState.recordSuccess(applicationContext, ZonedDateTime.now().toString())
            Result.success()
        } catch (e: Exception) {
            AutoSyncState.recordError(
                applicationContext,
                e.message ?: e.javaClass.simpleName
            )

            if (runAttemptCount < 5) Result.retry() else if (oneShot) Result.failure() else Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "health_sync"
        private const val KEY_ONE_SHOT = "one_shot"

        fun schedule(context: Context, intervalMinutes: Long = AutoSyncState.intervalMinutes(context)) {
            require(intervalMinutes in AutoSyncState.ALLOWED_INTERVALS)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_ONE_SHOT to true))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
