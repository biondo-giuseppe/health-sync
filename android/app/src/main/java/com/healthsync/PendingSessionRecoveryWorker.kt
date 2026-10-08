package com.healthsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PendingSessionRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!SessionBridge.isLinked(applicationContext)) return Result.success()

        val health = PrivateHealthManager(applicationContext)
        val hasPermissions = runCatching { health.hasPermissions() }.getOrDefault(false)
        if (!hasPermissions) return Result.success()

        return try {
            val target = SessionBridge.findRecoverTarget(applicationContext) ?: return Result.success()
            val heartRate = PersonalSessionStore.readHeartRateContext(
                applicationContext,
                target.startedAt,
                target.endedAt,
            )
            SessionBridge.sendHeartRateUpdate(applicationContext, target.id, heartRate)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK = "private-session-background-recovery"
        private const val RUN_NOW_WORK = "private-session-background-recovery-now"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<PendingSessionRecoveryWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingSessionRecoveryWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RUN_NOW_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
