package com.healthsync

import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)

        return try {

            val manager = PreferredHealthConnectManager(applicationContext)

            if (!manager.hasPermissions()) {
                AutoSyncState.recordError(applicationContext, "Health Connect permissions missing")
                return if (oneShot) Result.failure() else Result.success()
            }

            val snapshot = manager.readTodaySnapshot()
            val compactSnapshot = SyncPayload.compactForBackground(snapshot)

            withContext(Dispatchers.IO) {
                DriveClient.syncSnapshot(applicationContext, compactSnapshot)
            }

            DriveWriteVerifier.awaitRecordedAt(
                applicationContext,
                compactSnapshot.recordedAt
            )

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
