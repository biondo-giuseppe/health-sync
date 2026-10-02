package com.healthsync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class MainActivity : AppCompatActivity() {

    private lateinit var healthManager: PreferredHealthConnectManager
    private lateinit var statusText: TextView
    private lateinit var scheduleButton: Button
    private lateinit var intervalSpinner: Spinner
    private lateinit var diagnosticsText: TextView
    private var staleRecoveryRequestedThisResume = false

    private val intervalOptions = listOf(
        "15 minutes" to 15L,
        "30 minutes" to 30L,
        "1 hour" to 60L,
        "8 hours" to 480L,
    )

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lifecycleScope.launch {
            if (healthManager.hasPermissions()) {
                updateStatus("Health Connect connected.")
            } else if (granted.isNotEmpty()) {
                updateStatus("Health Connect partially connected. Some health fields may show blank.")
            } else {
                updateStatus("Health Connect permissions are still off. Opening Health Connect settings...")
                openHealthConnectPermissions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        healthManager = PreferredHealthConnectManager(this)
        statusText = findViewById(R.id.statusText)
        scheduleButton = findViewById(R.id.btnSchedule)
        intervalSpinner = findViewById(R.id.syncIntervalSpinner)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.1.0"
        findViewById<TextView>(R.id.versionText).text = "Version $versionName"

        findViewById<ViewGroup>(R.id.contentStack).scheduleLayoutAnimation()
        findViewById<View>(R.id.contentRoot).animate().alpha(1f).setDuration(260).start()

        configureIntervalSpinner()

        if (AutoSyncState.isEnabled(this)) {
            SyncWorker.ensureScheduled(this, AutoSyncState.intervalMinutes(this))
        }
        refreshStatusDisplay()

        findViewById<Button>(R.id.btnConnectHealth).setOnClickListener {
            lifecycleScope.launch {
                when (healthManager.availability()) {
                    HealthConnectManager.Availability.AVAILABLE -> {
                        try {
                            if (!healthManager.hasPermissions()) {
                                healthPermissionLauncher.launch(healthManager.permissions)
                            } else {
                                updateStatus("Health Connect already connected.")
                            }
                        } catch (e: Exception) {
                            updateStatus("Health Connect connection failed.")
                        }
                    }
                    HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> openHealthConnectInstall()
                    HealthConnectManager.Availability.UNAVAILABLE -> updateStatus(
                        "Health Connect is not available on this phone. Update Android and Google Play services, then try again."
                    )
                }
            }
        }

        findViewById<Button>(R.id.btnConnectDrive).setOnClickListener {
            try {
                updateStatus("Choose Google Drive and save as health_data.json.")
                @Suppress("DEPRECATION")
                startActivityForResult(createDriveFileIntent(), RC_DRIVE_FILE)
            } catch (e: ActivityNotFoundException) {
                updateStatus("No file picker found. Install Google Drive and try again.")
            }
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            lifecycleScope.launch {
                if (!healthManager.hasPermissions()) {
                    updateStatus("Connect Health Connect first.")
                    openHealthConnectPermissions()
                    return@launch
                }
                if (!DriveClient.hasFile(this@MainActivity)) {
                    updateStatus("Connect Google Drive first.")
                    return@launch
                }
                updateStatus("Syncing to Google Drive...")
                try {
                    val snapshot = healthManager.readTodaySnapshot()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        DriveClient.syncSnapshot(applicationContext, snapshot)
                    }
                    AutoSyncState.recordSuccess(this@MainActivity, ZonedDateTime.now().toString())
                    updateStatus(
                        "Synced to Drive!\n" +
                            "Primary source: ${sourceLabel(snapshot.selectedSummaryOrigin)}\n" +
                            "Steps: ${snapshot.steps ?: "--"}\n" +
                            "HR: ${snapshot.heartRateAvg ?: "--"} bpm\n" +
                            "Calories: ${snapshot.caloriesTotal ?: "--"} kcal\n" +
                            "Sleep: ${snapshot.sleepDurationMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "--"}"
                    )
                } catch (e: Exception) {
                    AutoSyncState.recordError(this@MainActivity, e.message ?: e.javaClass.simpleName)
                    updateStatus("Sync failed. Check connections and try again.")
                }
            }
        }

        scheduleButton.setOnClickListener {
            lifecycleScope.launch {
                if (AutoSyncState.isEnabled(this@MainActivity)) {
                    AutoSyncState.setEnabled(this@MainActivity, false)
                    SyncWorker.stop(this@MainActivity)
                    refreshStatusDisplay()
                    return@launch
                }

                if (!healthManager.hasPermissions()) {
                    updateStatus("Connect Health Connect before starting auto sync.")
                    openHealthConnectPermissions()
                    return@launch
                }
                if (!DriveClient.hasFile(this@MainActivity)) {
                    updateStatus("Connect Google Drive before starting auto sync.")
                    return@launch
                }

                val minutes = selectedIntervalMinutes()
                AutoSyncState.setIntervalMinutes(this@MainActivity, minutes)
                AutoSyncState.setEnabled(this@MainActivity, true)
                SyncWorker.schedule(this@MainActivity, minutes)
                SyncWorker.runOnce(this@MainActivity, trigger = "auto-sync-start")
                requestBatteryOptimizationExemption()
                refreshStatusDisplay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        staleRecoveryRequestedThisResume = false
        if (::healthManager.isInitialized && ::statusText.isInitialized) {
            recoverStaleSyncIfNeeded()
            refreshStatusDisplay()
        }
    }

    private fun recoverStaleSyncIfNeeded() {
        if (staleRecoveryRequestedThisResume) return
        if (!AutoSyncState.isEnabled(this)) return
        if (!DriveClient.hasFile(this)) return
        if (!AutoSyncState.isStale(this)) return

        staleRecoveryRequestedThisResume = true
        SyncWorker.runOnce(this, trigger = "stale-on-open")
    }

    @Deprecated("Uses legacy activity result API for document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_DRIVE_FILE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                DriveClient.saveFileUri(this, uri, data.flags)
                updateStatus("Google Drive file connected.\nYour file: health_data.json")
                refreshStatusDisplay()
            } else {
                updateStatus("Google Drive file selection cancelled.")
            }
        }
    }

    private fun configureIntervalSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervalOptions.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        intervalSpinner.adapter = adapter

        val saved = AutoSyncState.intervalMinutes(this)
        intervalSpinner.setSelection(intervalOptions.indexOfFirst { it.second == saved }.coerceAtLeast(0))

        intervalSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val minutes = intervalOptions[position].second
                val current = AutoSyncState.intervalMinutes(this@MainActivity)
                if (minutes == current) return
                AutoSyncState.setIntervalMinutes(this@MainActivity, minutes)
                if (AutoSyncState.isEnabled(this@MainActivity)) {
                    SyncWorker.schedule(this@MainActivity, minutes)
                    refreshStatusDisplay()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
    }

    private fun selectedIntervalMinutes(): Long =
        intervalOptions.getOrNull(intervalSpinner.selectedItemPosition)?.second
            ?: AutoSyncState.DEFAULT_INTERVAL_MINUTES

    private fun updateStatus(message: String) {
        statusText.text = message
        statusText.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_in))
    }

    private fun refreshStatusDisplay() {
        val hasDriveFile = DriveClient.hasFile(this)
        val autoSyncEnabled = AutoSyncState.isEnabled(this)
        val interval = AutoSyncState.intervalMinutes(this)
        val stale = AutoSyncState.isStale(this)
        val age = AutoSyncState.minutesSinceLastSuccess(this)

        scheduleButton.text = if (autoSyncEnabled) "Stop Auto Sync" else "Start Auto Sync"
        intervalSpinner.isEnabled = true

        lifecycleScope.launch {
            val healthAvailability = healthManager.availability()
            val hasHealth = runCatching { healthManager.hasPermissions() }.getOrDefault(false)
            val workerState = runCatching {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
                    .first()
                    .firstOrNull { !it.state.isFinished }
                    ?.state
            }.getOrNull()

            statusText.text = buildString {
                appendLine("Health Connect: ${healthStatusText(healthAvailability, hasHealth)}")
                appendLine("Google Drive: ${if (hasDriveFile) "File connected" else "Tap button below"}")
                appendLine("Primary wearable: Zepp/Amazfit")
                appendLine("Auto Sync request: ${if (autoSyncEnabled) "Every ${intervalLabel(interval)} (best effort)" else "Off"}")
                appendLine("Worker: ${workerState?.name ?: if (autoSyncEnabled) "Pending" else "Off"}")
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                appendLine("Battery optimization: ${if (powerManager.isIgnoringBatteryOptimizations(packageName)) "excluded" else "active"}")
                if (autoSyncEnabled && stale) appendLine("Sync status: stale — recovery requested")
                else if (autoSyncEnabled && age != null) appendLine("Sync status: OK · ${age} min ago")
                AutoSyncState.lastSuccess(this@MainActivity)?.let { appendLine("Last success: $it") }
                AutoSyncState.lastError(this@MainActivity)?.let { appendLine("Last error: $it") }
                if (PendingDriveCache.hasPending(this@MainActivity)) appendLine("Pending local upload: yes")
            }
            diagnosticsText.text = buildString {
                appendLine("Recent sync attempts")
                append(SyncDiagnostics.summary(this@MainActivity))
            }
        }
    }

    private fun intervalLabel(minutes: Long): String = when (minutes) {
        60L -> "1 hour"
        480L -> "8 hours"
        else -> "$minutes minutes"
    }

    private fun sourceLabel(origin: String?): String = when (origin) {
        PreferredHealthConnectManager.ZEPP_PACKAGE -> "Zepp/Amazfit"
        null -> "Health Connect fallback"
        else -> "Health Connect fallback"
    }

    private fun openHealthConnectInstall() {
        updateStatus("Health Connect needs to be installed or updated. Opening Play Store...")
        try {
            startActivity(healthManager.installOrUpdateIntent())
        } catch (e: ActivityNotFoundException) {
            updateStatus("Open Play Store and install or update Health Connect, then try again.")
        }
    }

    private fun openHealthConnectPermissions() {
        try {
            startActivity(healthManager.managePermissionsIntent())
        } catch (e: ActivityNotFoundException) {
            updateStatus(
                "Open Health Connect settings manually:\n" +
                    "Settings > Security & privacy > Privacy > Health Connect > App permissions > Health Sync"
            )
        }
    }

    private fun healthStatusText(
        availability: HealthConnectManager.Availability,
        hasPermissions: Boolean
    ): String {
        return when {
            hasPermissions -> "Connected"
            availability == HealthConnectManager.Availability.AVAILABLE -> "Tap button below"
            availability == HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> "Install or update required"
            else -> "Unavailable on this phone"
        }
    }

    private fun createDriveFileIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "health_data.json")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    companion object {
        private const val RC_DRIVE_FILE = 100
    }
}