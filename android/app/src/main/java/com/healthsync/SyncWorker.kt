package com.healthsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val oneShot = inputData.getBoolean(KEY_ONE_SHOT, false)

        return try {
            // Keep foreground setup inside the guarded block: some Android builds can reject
            // a background foreground-service start. That must never permanently kill periodic sync.
            setForeground(createForegroundInfo())

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

            if (oneShot) {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            } else {
                // Important: a periodic WorkManager job that returns FAILURE is finished forever.
                // Preserve the periodic chain after transient Health Connect / Drive / FGS errors.
                Result.success()
            }
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("Health Sync")
            .setContentText("Updating health_data.json")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Health sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Health Sync is updating your Drive export."
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val WORK_NAME = "health_sync"
        private const val CHANNEL_ID = "health_sync_background"
        private const val NOTIFICATION_ID = 1001
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
