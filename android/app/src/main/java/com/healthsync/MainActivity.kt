package com.healthsync

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var healthManager: PrivateHealthManager
    private lateinit var sessionStatus: TextView
    private lateinit var sessionSummary: TextView
    private lateinit var resultBadge: TextView
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var intensitySeek: SeekBar
    private lateinit var controlSeek: SeekBar
    private lateinit var energySeek: SeekBar
    private lateinit var wellbeingSeek: SeekBar
    private lateinit var intensityValue: TextView
    private lateinit var controlValue: TextView
    private lateinit var energyValue: TextView
    private lateinit var wellbeingValue: TextView

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshHealthStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AutoSyncState.setEnabled(this, false)
        SyncWorker.stop(this)
        healthManager = PrivateHealthManager(this)

        sessionStatus = findViewById(R.id.sessionStatus)
        sessionSummary = findViewById(R.id.sessionSummary)
        resultBadge = findViewById(R.id.resultBadge)
        startButton = findViewById(R.id.btnStartSession)
        endButton = findViewById(R.id.btnEndSession)
        analyzeButton = findViewById(R.id.btnAnalyze)
        intensitySeek = findViewById(R.id.seekIntensity)
        controlSeek = findViewById(R.id.seekControl)
        energySeek = findViewById(R.id.seekEnergy)
        wellbeingSeek = findViewById(R.id.seekWellbeing)
        intensityValue = findViewById(R.id.valueIntensity)
        controlValue = findViewById(R.id.valueControl)
        energyValue = findViewById(R.id.valueEnergy)
        wellbeingValue = findViewById(R.id.valueWellbeing)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.2.0"
        findViewById<TextView>(R.id.versionText).text = "Version $versionName"

        bindSeek(intensitySeek, intensityValue)
        bindSeek(controlSeek, controlValue)
        bindSeek(energySeek, energyValue)
        bindSeek(wellbeingSeek, wellbeingValue)

        findViewById<Button>(R.id.btnConnectHealth).setOnClickListener {
            lifecycleScope.launch {
                when (healthManager.availability()) {
                    HealthConnectManager.Availability.AVAILABLE -> {
                        if (!healthManager.hasPermissions()) healthPermissionLauncher.launch(healthManager.permissions)
                        else refreshHealthStatus()
                    }
                    HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> {
                        try { startActivity(healthManager.installOrUpdateIntent()) }
                        catch (_: ActivityNotFoundException) { sessionStatus.text = "Health Connect va installato o aggiornato." }
                    }
                    HealthConnectManager.Availability.UNAVAILABLE -> sessionStatus.text = "Health Connect non disponibile su questo telefono."
                }
            }
        }

        startButton.setOnClickListener {
            lifecycleScope.launch {
                if (!healthManager.hasPermissions()) {
                    sessionStatus.text = "Prima collega Health Connect."
                    healthPermissionLauncher.launch(healthManager.permissions)
                    return@launch
                }
                val started = PersonalSessionStore.start(this@MainActivity)
                sessionStatus.text = "Sessione attiva dalle ${formatTime(started)}"
                resultBadge.text = "IN CORSO"
                resultBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_blue))
                startButton.isEnabled = false
                endButton.isEnabled = true
                analyzeButton.isEnabled = false
            }
        }

        endButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    sessionStatus.text = "Sto leggendo i dati della sessione..."
                    val summary = PersonalSessionStore.finish(
                        context = this@MainActivity,
                        intensity = score(intensitySeek),
                        control = score(controlSeek),
                        energy = score(energySeek),
                        wellbeing = score(wellbeingSeek),
                    )
                    renderSummary(summary)
                } catch (e: Exception) {
                    sessionStatus.text = "Impossibile chiudere la sessione: ${e.message ?: "errore"}"
                }
            }
        }

        analyzeButton.setOnClickListener {
            val summary = PersonalSessionStore.lastSummary(this) ?: return@setOnClickListener
            val payload = buildString {
                appendLine("Analizza questa sessione personale in modo riservato.")
                appendLine("Valuta andamento, eventuali pattern utili e suggerimenti pratici per benessere, controllo e durata.")
                appendLine("Non formulare diagnosi mediche.")
                appendLine()
                append(PersonalSessionStore.shareText(summary))
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
            }
            startActivity(Intent.createChooser(intent, "Analizza"))
        }

        refreshHealthStatus()
        restoreSessionState()
    }

    override fun onResume() {
        super.onResume()
        if (::healthManager.isInitialized) {
            refreshHealthStatus()
            restoreSessionState()
        }
    }

    private fun bindSeek(seek: SeekBar, value: TextView) {
        seek.max = 4
        seek.progress = 2
        value.text = "3/5"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = "${progress + 1}/5"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun score(seek: SeekBar): Int = seek.progress + 1

    private fun restoreSessionState() {
        val active = PersonalSessionStore.activeStart(this)
        if (active != null) {
            sessionStatus.text = "Sessione attiva dalle ${formatTime(active)}"
            resultBadge.text = "IN CORSO"
            resultBadge.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
            startButton.isEnabled = false
            endButton.isEnabled = true
            analyzeButton.isEnabled = false
        } else {
            startButton.isEnabled = true
            endButton.isEnabled = false
            val last = PersonalSessionStore.lastSummary(this)
            if (last != null) renderSummary(last)
            else {
                resultBadge.text = "PRONTO"
                resultBadge.setTextColor(ContextCompat.getColor(this, R.color.teal_dark))
                sessionStatus.text = "Pronto per una nuova sessione."
                sessionSummary.text = "I dati restano locali finché non scegli Analizza."
                analyzeButton.isEnabled = false
            }
        }
    }

    private fun renderSummary(summary: PersonalSessionSummary) {
        sessionStatus.text = "Ultima sessione completata"
        sessionSummary.text = buildString {
            appendLine("Durata  ${summary.durationMinutes} min")
            appendLine("Battito medio  ${summary.heartRateAvg?.let { "$it bpm" } ?: "n/d"}")
            appendLine("Intervallo  ${summary.heartRateMin?.let { "$it" } ?: "n/d"}–${summary.heartRateMax?.let { "$it bpm" } ?: "n/d"}")
            appendLine("Intensità  ${summary.intensity}/5   Controllo  ${summary.control}/5")
            append("Energia  ${summary.energy}/5   Benessere  ${summary.wellbeing}/5")
        }
        resultBadge.text = summary.status
        val color = when (summary.status) {
            "MIGLIORA" -> R.color.status_green
            "ATTENZIONE" -> R.color.status_amber
            else -> R.color.status_teal
        }
        resultBadge.setTextColor(ContextCompat.getColor(this, color))
        startButton.isEnabled = true
        endButton.isEnabled = false
        analyzeButton.isEnabled = true
    }

    private fun refreshHealthStatus() {
        lifecycleScope.launch {
            val connected = runCatching { healthManager.hasPermissions() }.getOrDefault(false)
            findViewById<TextView>(R.id.healthStatus).text =
                if (connected) "● Health Connect collegato" else "○ Health Connect da collegare"
        }
    }

    private fun formatTime(instant: java.time.Instant): String =
        ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
}
