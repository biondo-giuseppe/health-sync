package com.healthsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class SyncWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AutoSyncState.isEnabled(context)) return
        schedule(context)

        if (AutoSyncState.isStale(context)) {
            SyncWorker.runOnce(context, trigger = "watchdog")
        }
    }

    companion object {
        private const val REQUEST_CODE = 41015
        private const val WATCHDOG_INTERVAL_MS = 30L * 60L * 1000L

        fun schedule(context: Context) {
            if (!AutoSyncState.isEnabled(context)) return
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, SyncWatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
