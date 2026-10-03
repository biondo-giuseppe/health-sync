package com.healthsync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = processMutex.withLock {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?: if (inputData.getBoolean(KEY_ONE_SHOT, false)) "manual-recovery" else "periodic"
        val diagnostic = SyncDiagnostics.start(applicationContext, trigger, runAttemptCount)
        var phase = "preflight"

        try {
            val manager = PreferredHealthConnectManager(applicationContext)

            if (!AutoSyncState.isEnabled(applicationContext) && trigger != "manual") {
                SyncDiagnostics.permanent(applicationContext, diagnostic, phase, "Auto Sync disabled")
                return@withLock Result.success()
            }

            if (!SupabaseDirectClient.isPaired(applicationContext)) {
                AutoSyncState.recordError(applicationContext, "Supabase not paired")
                SyncDiagnostics.permanent(applicationContext, diagnostic, phase, "Supabase not paired")
                return@withLock Result.success()
            }

            if (!manager.hasPermissions()) {
                AutoSyncState.recordError(applicationContext, "Health Connect permissions missing")
                SyncDiagnostics.permanent(applicationContext, diagnostic, phase, "Health Connect permissions missing")
                return@withLock Result.success()
            }

            // First recover any fully prepared payload left behind by an earlier network/API failure.
            phase = "pending-supabase-upload"
            SyncDiagnostics.phase(applicationContext, diagnostic, phase)
            val pendingRecordedAt = withContext(Dispatchers.IO) {
                SupabaseDirectClient.flushPending(applicationContext)
            }
            if (pendingRecordedAt != null) {
                PendingSupabaseCache.clear(applicationContext)
            }

            phase = "health-connect-read"
            SyncDiagnostics.phase(applicationContext, diagnostic, phase)
            val snapshot = manager.readTodaySnapshot(backgroundCompact = true)
            val compactSnapshot = SyncPayload.compactForBackground(snapshot)

            phase = "local-cache"
            SyncDiagnostics.phase(applicationContext, diagnostic, phase)
            withContext(Dispatchers.IO) {
                SupabaseDirectClient.prepareAndCache(applicationContext, compactSnapshot)
            }

            phase = "supabase-upload"
            SyncDiagnostics.phase(applicationContext, diagnostic, phase)
            withContext(Dispatchers.IO) {
                SupabaseDirectClient.flushPending(applicationContext)
                    ?: throw IllegalStateException("Prepared Supabase payload disappeared")
            }
            PendingSupabaseCache.clear(applicationContext)

            val successAt = ZonedDateTime.now().toString()
            AutoSyncState.recordSuccess(applicationContext, successAt)
            SyncDiagnostics.success(
                applicationContext,
                diagnostic,
                compactSnapshot.recordedAt,
                compactSnapshot.steps,
                compactSnapshot.zeppStepsLastModifiedAt,
                compactSnapshot.zeppStepsLatestEndAt,
            )
            SyncWatchdogReceiver.schedule(applicationContext)
            Result.success()
        } catch (e: SecurityException) {
            AutoSyncState.recordError(applicationContext, e.message ?: "Permission error")
            SyncDiagnostics.permanent(applicationContext, diagnostic, phase, e.message ?: "Permission error")
            Result.success()
        } catch (e: Exception) {
            AutoSyncState.recordError(
                applicationContext,
                "${phase}: ${e.message ?: e.javaClass.simpleName}"
            )
            SyncDiagnostics.failure(applicationContext, diagnostic, phase, e, retry = true)
            // Do not report a false success. WorkManager will use exponential backoff,
            // while the independent watchdog remains available if scheduling is delayed.
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "health_sync"
        private const val RECOVERY_WORK_NAME = "health_sync_recovery"
        private const val KEY_ONE_SHOT = "one_shot"
        private const val KEY_TRIGGER = "trigger"
        private val processMutex = Mutex()

        fun schedule(context: Context, intervalMinutes: Long = AutoSyncState.intervalMinutes(context)) {
            enqueuePeriodic(context, intervalMinutes, ExistingPeriodicWorkPolicy.UPDATE)
        }

        fun ensureScheduled(context: Context, intervalMinutes: Long = AutoSyncState.intervalMinutes(context)) {
            enqueuePeriodic(context, intervalMinutes, ExistingPeriodicWorkPolicy.KEEP)
        }

        private fun enqueuePeriodic(
            context: Context,
            intervalMinutes: Long,
            policy: ExistingPeriodicWorkPolicy,
        ) {
            require(intervalMinutes in AutoSyncState.ALLOWED_INTERVALS)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to "periodic"))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                policy,
                request
            )
            SyncWatchdogReceiver.schedule(context)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(RECOVERY_WORK_NAME)
            SyncWatchdogReceiver.cancel(context)
        }

        fun runOnce(context: Context, trigger: String = "manual-recovery") {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ONE_SHOT to true,
                        KEY_TRIGGER to trigger,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                RECOVERY_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
