package com.healthsync

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var saveButton: Button
    private lateinit var recoverButton: Button
    private lateinit var retryUploadButton: Button
    private lateinit var feedbackContainer: LinearLayout
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
        saveButton = findViewById(R.id.btnSaveSession)
        recoverButton = findViewById(R.id.btnRecoverSession)
        retryUploadButton = findViewById(R.id.btnRetryUpload)
        feedbackContainer = findViewById(R.id.feedbackContainer)
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
        }.getOrNull() ?: "1.2.4"
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
                    refreshLinkStatus()
                }
            }
        }

        recoverButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    recoverButton.isEnabled = false
                    sessionStatus.text = "Cerco l'ultima sessione senza battito…"

                    if (!SessionBridge.isLinked(this@MainActivity)) {
                        error("Prima collega Salute.")
                    }
                    if (!healthManager.hasPermissions()) {
                        error("Prima collega Health Connect.")
                    }

                    val target = SessionBridge.findRecoverTarget(this@MainActivity)
                    if (target == null) {
                        sessionStatus.text = "Nessuna sessione recente da recuperare."
                        return@launch
                    }

                    sessionStatus.text = "Leggo i dati cardiaci della sessione…"
                    val heartRate = PersonalSessionStore.readHeartRateContext(
                        this@MainActivity,
                        target.startedAt,
                        target.endedAt,
                    )

                    val hasAny = heartRate.session.samples > 0 ||
                        heartRate.pre.samples > 0 ||
                        heartRate.post.samples > 0

                    if (!hasAny) {
                        sessionStatus.text = "Nessun campione cardiaco trovato per quella finestra."
                        return@launch
                    }

                    SessionBridge.sendHeartRateUpdate(this@MainActivity, target.id, heartRate)
                    sessionStatus.text = "Dati cardiaci recuperati e associati alla sessione."
                    sessionSummary.text = buildString {
                        appendLine("Prima  " + (heartRate.pre.avg?.let { it.toString() + " bpm" } ?: "n/d"))
                        appendLine("Sessione  " + (heartRate.session.avg?.let { it.toString() + " bpm" } ?: "n/d"))
                        append("Dopo  " + (heartRate.post.avg?.let { it.toString() + " bpm" } ?: "n/d"))
                    }
                } catch (e: Exception) {
                    sessionStatus.text = e.message ?: "Recupero non riuscito."
                } finally {
                    recoverButton.isEnabled = true
                }
            }
        }

        retryUploadButton.setOnClickListener {
            lifecycleScope.launch {
                val summary = PersonalSessionStore.retryCandidate(this@MainActivity)
                if (summary == null) {
                    retryUploadButton.visibility = View.GONE
                    sessionStatus.text = "Nessuna sessione da reinviare."
                    return@launch
                }

                try {
                    retryUploadButton.isEnabled = false
                    sessionStatus.text = "Riprovo a inviare la sessione…"
                    PersonalSessionStore.markPendingUpload(this@MainActivity, summary)
                    val sent = SessionBridge.send(this@MainActivity, summary)
                    PersonalSessionStore.markUploaded(this@MainActivity, summary)
                    HeartRateRetryWorker.schedule(
                        this@MainActivity,
                        sent.id,
                        summary.startedAt,
                        summary.endedAt,
                    )
                    renderSummary(summary, sent.status)
                    sessionStatus.text =
                        if (summary.heartRateAvg == null)
                            "Sessione inviata. Battito in attesa di sincronizzazione."
                        else
                            "Sessione inviata in Salute."
                    retryUploadButton.visibility = View.GONE
                } catch (e: Exception) {
                    PersonalSessionStore.markPendingUpload(this@MainActivity, summary)
                    SessionUploadRetryWorker.schedule(this@MainActivity)
                    sessionStatus.text = "Invio ancora non riuscito: ${e.message ?: "errore"}. Riproverò automaticamente."
                    retryUploadButton.visibility = View.VISIBLE
                } finally {
                    retryUploadButton.isEnabled = true
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

                resetFeedback()
                feedbackContainer.visibility = View.GONE
                val started = PersonalSessionStore.start(this@MainActivity)
                sessionStatus.text = "Sessione attiva dalle ${formatTime(started)}"
                sessionSummary.text = "Al termine potrai inserire la tua valutazione."
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
                    sessionStatus.text = "Sessione terminata. Leggo i dati disponibili…"
                    val draft = PersonalSessionStore.stopForFeedback(this@MainActivity)
                    showDraftForFeedback(draft)
                } catch (e: Exception) {
                    sessionStatus.text = "Impossibile terminare la sessione: ${e.message ?: "errore"}"
                    endButton.isEnabled = true
                }
            }
        }

        saveButton.setOnClickListener {
            lifecycleScope.launch {
                saveButton.isEnabled = false
                try {
                    val summary = PersonalSessionStore.completeDraft(
                        context = this@MainActivity,
                        intensity = score(intensitySeek),
                        control = score(controlSeek),
                        energy = score(energySeek),
                        wellbeing = score(wellbeingSeek),
                    )

                    feedbackContainer.visibility = View.GONE
                    renderSummary(summary, null)
                    startButton.isEnabled = true
                    endButton.isEnabled = false
                    sessionStatus.text = "Sessione salvata sul telefono. Invio a Salute…"

                    try {
                        val sent = SessionBridge.send(this@MainActivity, summary)
                        PersonalSessionStore.markUploaded(this@MainActivity, summary)
                        HeartRateRetryWorker.schedule(
                            this@MainActivity,
                            sent.id,
                            summary.startedAt,
                            summary.endedAt,
                        )
                        renderSummary(summary, sent.status)
                        sessionStatus.text =
                            if (summary.heartRateAvg == null)
                                "Sessione salvata. Battito in attesa di sincronizzazione."
                            else
                                "Sessione salvata in Salute"
                        retryUploadButton.visibility = View.GONE
                    } catch (e: Exception) {
                        PersonalSessionStore.markPendingUpload(this@MainActivity, summary)
                        SessionUploadRetryWorker.schedule(this@MainActivity)
                        sessionStatus.text = "Sessione salvata sul telefono. Invio non riuscito: ${e.message ?: "errore"}. Riproverò automaticamente."
                        retryUploadButton.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    sessionStatus.text = "Salvataggio locale non riuscito: ${e.message ?: "errore"}"
                    saveButton.isEnabled = true
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

    private fun resetFeedback() {
        listOf(intensitySeek, controlSeek, energySeek, wellbeingSeek).forEach { it.progress = 2 }
        saveButton.isEnabled = true
    }

    private fun restoreSessionState() {
        val active = PersonalSessionStore.activeStart(this)
        val pending = PersonalSessionStore.pendingDraft(this)
        val retryCandidate = PersonalSessionStore.retryCandidate(this)
        retryUploadButton.visibility = if (retryCandidate != null) View.VISIBLE else View.GONE

        when {
            active != null -> {
                feedbackContainer.visibility = View.GONE
                sessionStatus.text = "Sessione attiva dalle ${formatTime(active)}"
                resultBadge.text = "IN CORSO"
                resultBadge.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
                startButton.isEnabled = false
                endButton.isEnabled = true
            }
            pending != null -> {
                showDraftForFeedback(pending)
            }
            else -> {
                feedbackContainer.visibility = View.GONE
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
    }

    private fun showDraftForFeedback(draft: PersonalSessionDraft) {
        feedbackContainer.visibility = View.VISIBLE
        startButton.isEnabled = false
        endButton.isEnabled = false
        saveButton.isEnabled = true
        resultBadge.text = "DA VALUTARE"
        resultBadge.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
        sessionStatus.text = "Sessione terminata. Inserisci ora i 4 valori."
        sessionSummary.text = buildString {
            appendLine("Durata  ${draft.durationMinutes} min")
            appendLine("Battito medio  ${draft.heartRateAvg?.let { "$it bpm" } ?: "in attesa"}")
            append("Poi premi Salva in Salute.")
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
            appendLine("Battito medio  ${summary.heartRateAvg?.let { "$it bpm" } ?: "in attesa"}")
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
