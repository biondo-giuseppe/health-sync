package com.healthsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AutoSyncState.isEnabled(context)) return

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SyncWorker.schedule(context, AutoSyncState.intervalMinutes(context))
        } else {
            SyncWorker.ensureScheduled(context, AutoSyncState.intervalMinutes(context))
        }
        SyncWatchdogReceiver.schedule(context)
        SyncWorker.runOnce(
            context,
            trigger = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "app-update" else "boot",
        )
    }
}
