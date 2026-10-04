package com.healthsync

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
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
    private lateinit var linkStatus: TextView
    private lateinit var pairingCode: EditText
    private lateinit var pairButton: Button
    private lateinit var startButton: Button
    private lateinit var endButton: Button
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
        linkStatus = findViewById(R.id.linkStatus)
        pairingCode = findViewById(R.id.pairingCode)
        pairButton = findViewById(R.id.btnPair)
        startButton = findViewById(R.id.btnStartSession)
        endButton = findViewById(R.id.btnEndSession)
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
        }.getOrNull() ?: "1.2.1"
        findViewById<TextView>(R.id.versionText).text = "Version $versionName"

        bindSeek(intensitySeek, intensityValue)
        bindSeek(controlSeek, controlValue)
        bindSeek(energySeek, energyValue)
        bindSeek(wellbeingSeek, wellbeingValue)

        findViewById<Button>(R.id.btnConnectHealth).setOnClickListener {
            lifecycleScope.launch {
                when (healthManager.availability()) {
                    HealthConnectManager.Availability.AVAILABLE -> {
                        if (!healthManager.hasPermissions()) {
                            healthPermissionLauncher.launch(healthManager.permissions)
                        } else {
                            refreshHealthStatus()
                        }
                    }
                    HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> {
                        try {
                            startActivity(healthManager.installOrUpdateIntent())
                        } catch (_: ActivityNotFoundException) {
                            sessionStatus.text = "Health Connect va installato o aggiornato."
                        }
                    }
                    HealthConnectManager.Availability.UNAVAILABLE -> {
                        sessionStatus.text = "Health Connect non disponibile su questo telefono."
                    }
                }
            }
        }

        pairButton.setOnClickListener {
            val code = pairingCode.text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
                try {
                    pairButton.isEnabled = false
                    linkStatus.text = "Collegamento in corso…"
                    SessionBridge.link(this@MainActivity, code)
                    pairingCode.setText("")
                    refreshLinkStatus()
                    sessionStatus.text = "Salute collegata. Il telefono è pronto."
                } catch (e: Exception) {
                    linkStatus.text = "○ Salute non collegata"
                    sessionStatus.text = e.message ?: "Collegamento non riuscito."
                } finally {
                    pairButton.isEnabled = true
                }
            }
        }

        startButton.setOnClickListener {
            lifecycleScope.launch {
                if (!SessionBridge.isLinked(this@MainActivity)) {
                    sessionStatus.text = "Prima collega Salute con il codice a 6 cifre."
                    return@launch
                }
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
            }
        }

        endButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    endButton.isEnabled = false
                    sessionStatus.text = "Leggo i dati e aggiorno Salute…"

                    val summary = PersonalSessionStore.finish(
                        context = this@MainActivity,
                        intensity = score(intensitySeek),
                        control = score(controlSeek),
                        energy = score(energySeek),
                        wellbeing = score(wellbeingSeek),
                    )

                    val remoteStatus = SessionBridge.send(this@MainActivity, summary)
                    renderSummary(summary, remoteStatus)
                    sessionStatus.text = "Sessione salvata in Salute"
                } catch (e: Exception) {
                    val last = PersonalSessionStore.lastSummary(this@MainActivity)
                    if (last != null) renderSummary(last, null)
                    sessionStatus.text = "Sessione conservata sul telefono. Invio non riuscito: ${e.message ?: "errore"}"
                } finally {
                    startButton.isEnabled = true
                    endButton.isEnabled = false
                    refreshLinkStatus()
                }
            }
        }

        refreshHealthStatus()
        refreshLinkStatus()
        restoreSessionState()
    }

    override fun onResume() {
        super.onResume()
        if (::healthManager.isInitialized) {
            refreshHealthStatus()
            refreshLinkStatus()
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
        } else {
            startButton.isEnabled = true
            endButton.isEnabled = false
            val last = PersonalSessionStore.lastSummary(this)
            if (last != null) {
                renderSummary(last, null)
            } else {
                resultBadge.text = "PRONTO"
                resultBadge.setTextColor(ContextCompat.getColor(this, R.color.teal_dark))
                sessionStatus.text = "Pronto per una nuova sessione."
                sessionSummary.text = "Durata e battito saranno letti automaticamente."
            }
        }
    }

    private fun renderSummary(summary: PersonalSessionSummary, remoteStatus: String?) {
        val displayStatus = when (remoteStatus) {
            "improving" -> "MIGLIORA"
            "attention" -> "ATTENZIONE"
            "stable" -> "STABILE"
            else -> summary.status
        }

        sessionSummary.text = buildString {
            appendLine("Durata  ${summary.durationMinutes} min")
            appendLine("Battito medio  ${summary.heartRateAvg?.let { "$it bpm" } ?: "n/d"}")
            appendLine("Intervallo  ${summary.heartRateMin?.let { "$it" } ?: "n/d"}–${summary.heartRateMax?.let { "$it bpm" } ?: "n/d"}")
            appendLine("Intensità  ${summary.intensity}/5   Controllo  ${summary.control}/5")
            append("Energia  ${summary.energy}/5   Benessere  ${summary.wellbeing}/5")
        }

        resultBadge.text = displayStatus
        val color = when (displayStatus) {
            "MIGLIORA" -> R.color.status_green
            "ATTENZIONE" -> R.color.status_amber
            else -> R.color.status_teal
        }
        resultBadge.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun refreshHealthStatus() {
        lifecycleScope.launch {
            val connected = runCatching { healthManager.hasPermissions() }.getOrDefault(false)
            findViewById<TextView>(R.id.healthStatus).text =
                if (connected) "● Health Connect collegato" else "○ Health Connect da collegare"
        }
    }

    private fun refreshLinkStatus() {
        val linked = SessionBridge.isLinked(this)
        linkStatus.text = if (linked) "● Salute collegata" else "○ Salute non collegata"
        pairingCode.isEnabled = !linked
        pairButton.isEnabled = !linked
        if (linked) pairingCode.hint = "Dispositivo collegato"
    }

    private fun formatTime(instant: java.time.Instant): String =
        ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
}
