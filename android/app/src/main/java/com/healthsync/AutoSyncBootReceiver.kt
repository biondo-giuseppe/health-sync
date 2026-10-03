package com.healthsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (AutoSyncPrefs.isEnabled(context) && DriveClient.hasFile(context)) {
                    SyncWorker.schedule(context)
                }
            }
        }
    }
}
