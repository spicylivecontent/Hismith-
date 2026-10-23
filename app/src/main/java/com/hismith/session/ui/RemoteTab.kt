package com.hismith.session.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.hismith.session.MainActivity
import com.hismith.session.R
import com.hismith.session.UiState
import com.hismith.session.core.Source
import com.hismith.session.remote.KeystoreBox
import com.hismith.session.remote.RemoteHost
import java.security.SecureRandom

/** Remote tab: one-time link, relay server, and the rules for the person holding the link. */
class RemoteTab(private val a: MainActivity) {
    private val settings = a.settings

    private val status: TextView = a.findViewById(R.id.rmStatus)
    private val detail: TextView = a.findViewById(R.id.rmDetail)
    private val link: TextView = a.findViewById(R.id.rmLink)
    private val shareRow: View = a.findViewById(R.id.rmShareRow)
    private val approveRow: View = a.findViewById(R.id.rmApproveRow)
    private val btnStart: Button = a.findViewById(R.id.btnRmStart)
    private val btnStop: Button = a.findViewById(R.id.btnRmStop)
    private val btnKick: Button = a.findViewById(R.id.btnRmKick)
    private val btnUnlock: Button = a.findViewById(R.id.btnRmUnlock)
    private val url: EditText = a.findViewById(R.id.rmUrl)
    private val secret: EditText = a.findViewById(R.id.rmSecret)
    private val swApproval: Switch = a.findViewById(R.id.rmApproval)
    private val swLube: Switch = a.findViewById(R.id.rmAllowLube)
    private val seekMaxMl: SeekBar = a.findViewById(R.id.rmMaxMl)
    private val valMaxMl: TextView = a.findViewById(R.id.rmMaxMlVal)
    private val pumpNote: TextView = a.findViewById(R.id.rmPumpNote)
    private val swAuto: Switch = a.findViewById(R.id.rmAllowAuto)
    private val seekMax: SeekBar = a.findViewById(R.id.rmMaxSpeed)
    private val valMax: TextView = a.findViewById(R.id.rmMaxSpeedVal)
    private val seekStop: SeekBar = a.findViewById(R.id.rmStopSec)
    private val valStop: TextView = a.findViewById(R.id.rmStopSecVal)
    private val seekRevoke: SeekBar = a.findViewById(R.id.rmRevokeSec)
    private val valRevoke: TextView = a.findViewById(R.id.rmRevokeSecVal)
    private val testResult: TextView = a.findViewById(R.id.rmTestResult)

    companion object {
        /** One-tap Render deploy of relay-server/ (render.yaml in the repo root). */
        const val RENDER_DEPLOY_URL = "https://render.com/deploy?repo=https://github.com/spicylivecontent/Hismith-"
    }

    init {
        url.setText(settings.serverUrl)
        if (settings.hostSecretEncrypted != null) secret.hint = "Sparad (lämna tomt för att behålla)"
        val limits = settings.motionLimits
        swApproval.isChecked = settings.requireApproval
        swLube.isChecked = limits.remoteAllowLube
        seekMaxMl.progress = limits.remoteMaxMl - 1
        valMaxMl.text = "${limits.remoteMaxMl} ml"
        swAuto.isChecked = limits.remoteAllowAuto
        refreshPumpNote()
        seekMax.progress = limits.remoteMaxSpeed
        valMax.text = "${limits.remoteMaxSpeed} %"
        seekStop.progress = settings.failsafeStopSec - 2
        valStop.text = "${settings.failsafeStopSec} s"
        seekRevoke.progress = settings.failsafeRevokeSec - 3
        valRevoke.text = "${settings.failsafeRevokeSec} s"

        swApproval.setOnCheckedChangeListener { _, c -> settings.requireApproval = c }
        swLube.setOnCheckedChangeListener { _, c -> settings.motionLimits = settings.motionLimits.copy(remoteAllowLube = c); a.service?.settingsChanged() }
        swAuto.setOnCheckedChangeListener { _, c -> settings.motionLimits = settings.motionLimits.copy(remoteAllowAuto = c); a.service?.settingsChanged() }
        seekMaxMl.onChange({ v, _ -> valMaxMl.text = "${v + 1} ml" }, onStop = { v ->
            settings.motionLimits = settings.motionLimits.copy(remoteMaxMl = v + 1)
            a.service?.settingsChanged()
        })
        seekMax.onChange({ v, _ -> valMax.text = "$v %" }, onStop = { v ->
            settings.motionLimits = settings.motionLimits.copy(remoteMaxSpeed = v)
            a.service?.settingsChanged()
        })
        seekStop.onChange({ v, _ -> valStop.text = "${v + 2} s" }, onStop = { v ->
            settings.failsafeStopSec = v + 2
            if (settings.failsafeRevokeSec <= settings.failsafeStopSec) {
                settings.failsafeRevokeSec = settings.failsafeStopSec + 1
                seekRevoke.progress = settings.failsafeRevokeSec - 3
            }
            a.service?.settingsChanged()
        })
        seekRevoke.onChange({ v, _ -> valRevoke.text = "${v + 3} s" }, onStop = { v ->
            settings.failsafeRevokeSec = maxOf(v + 3, settings.failsafeStopSec + 1)
            seekRevoke.progress = settings.failsafeRevokeSec - 3
            a.service?.settingsChanged()
        })

        a.findViewById<Button>(R.id.btnRmSave).setOnClickListener { if (saveServer()) testServer() }
        a.findViewById<Button>(R.id.btnRmGenSecret).setOnClickListener { generateSecret() }
        a.findViewById<Button>(R.id.btnRmCopySecret).setOnClickListener {
            val sec = KeystoreBox.decrypt(settings.hostSecretEncrypted)
            if (sec == null) Ui.toast(a, "Ingen hemlighet sparad – tryck steg 1") else copy("Hemlighet", sec, "Hemligheten kopierad")
        }
        a.findViewById<Button>(R.id.btnRmOpenRender).setOnClickListener {
            if (settings.hostSecretEncrypted == null) generateSecret()
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RENDER_DEPLOY_URL)))
            } catch (_: Exception) {
                Ui.toast(a, "Ingen webbläsare hittades")
            }
        }
        btnStart.setOnClickListener {
            val st = a.state ?: return@setOnClickListener
            if (settings.serverUrl.isBlank()) saveServer()
            if (settings.serverUrl.isBlank()) return@setOnClickListener Ui.toast(a, "Fyll i serveradressen först")
            if (st.remote.status == RemoteHost.Status.OFF || st.remote.status == RemoteHost.Status.ERROR) a.service?.remoteStart()
            else a.service?.remoteNewLink()
        }
        btnStop.setOnClickListener { a.service?.remoteStop() }
        btnKick.setOnClickListener { a.service?.remoteKick() }
        btnUnlock.setOnClickListener { a.service?.remoteUnlock() }
        a.findViewById<Button>(R.id.btnRmApprove).setOnClickListener { a.service?.remoteApprove() }
        a.findViewById<Button>(R.id.btnRmDeny).setOnClickListener { a.service?.remoteDeny() }
        a.findViewById<Button>(R.id.btnRmShare).setOnClickListener {
            val l = a.state?.remote?.link ?: return@setOnClickListener
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, l)
            }, "Dela engångslänken"))
        }
        a.findViewById<Button>(R.id.btnRmCopy).setOnClickListener {
            val l = a.state?.remote?.link ?: return@setOnClickListener
            copy("Länk", l, "Länken kopierad")
        }
    }

    /** The pump slider on the recipient's page tops out at the safe max set in the Lube tab. */
    private fun refreshPumpNote() {
        val text = "Pumphastighet: 100 % hos mottagaren = din säkra maxhastighet (${settings.lubeSettings.maxPumpSpeed} %) under Lube – aldrig pumpens fulla kapacitet."
        if (pumpNote.text.toString() != text) pumpNote.text = text
    }

    private fun copy(label: String, text: String, toast: String) {
        a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Ui.toast(a, toast)
    }

    /** Step 1: a long random secret, stored encrypted and copied for pasting into Render. */
    private fun generateSecret() {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        settings.hostSecretEncrypted = KeystoreBox.encrypt(secret)
        secret.let { copy("Hemlighet", it, "Hemligheten är kopierad – klistra in den i HOST_SECRET på Render") }
        this.secret.setText("")
        this.secret.hint = "Sparad (lämna tomt för att behålla)"
    }

    /** Step 4: does the server answer, is it ours, and does it want a secret? */
    private fun testServer() {
        val url = settings.serverUrl
        if (url.isBlank()) return
        testResult.visibility = View.VISIBLE
        testResult.setTextColor(a.getColor(R.color.muted))
        testResult.text = "Testar servern… (en sovande gratisserver kan ta upp till en minut)"
        RemoteHost.probe(url) { r ->
            a.runOnUiThread {
                val hasSecret = settings.hostSecretEncrypted != null
                val (text, color) = when {
                    !r.ok -> "✕ ${r.error}" to R.color.red
                    r.secretRequired == false -> "⚠ Servern svarar (${Ui.num(r.ms / 1000.0)} s) men har ingen HOST_SECRET – vem som helst kan skapa länkar via den. Lägg till hemligheten på Render." to R.color.amber
                    !hasSecret -> "⚠ Servern kräver en hemlighet – fyll i samma HOST_SECRET här." to R.color.amber
                    else -> "✓ Servern fungerar (svar på ${Ui.num(r.ms / 1000.0)} s, version ${r.version}). Tryck Skapa fjärrlänk ovan!" to R.color.green
                }
                testResult.text = text
                testResult.setTextColor(a.getColor(color))
            }
        }
    }

    private fun saveServer(): Boolean {
        val s = a.service
        val typed = url.text.toString().trim()
        if (typed.isNotEmpty()) {
            val norm = RemoteHost.normalize(typed)
            if (norm == null) {
                Ui.toast(a, "Adressen måste vara https://…")
                return false
            }
            settings.serverUrl = norm
            url.setText(norm)
        }
        val sec = secret.text.toString()
        if (sec.isNotEmpty()) {
            settings.hostSecretEncrypted = KeystoreBox.encrypt(sec)
            secret.setText("")
            secret.hint = "Sparad (lämna tomt för att behålla)"
        }
        s?.settingsChanged()
        Ui.toast(a, "Server sparad")
        return true
    }

    fun render(st: UiState) {
        val r = st.remote
        status.text = if (r.status == RemoteHost.Status.ERROR) "Fel: ${r.error ?: "okänt"}" else r.status.label
        status.setTextColor(a.getColor(when {
            r.status == RemoteHost.Status.ERROR -> R.color.red
            r.controllerSilent -> R.color.amber
            r.controllerActive -> R.color.green
            else -> R.color.text
        }))
        detail.text = when {
            r.locked -> "Du tryckte STOPP – mottagaren kan inte styra förrän du släpper in den igen."
            r.controllerSilent -> "Mottagaren är tyst – allt den styr stoppas strax."
            r.controllerActive && st.owner == Source.REMOTE -> "Mottagaren styr just nu. STOPP på telefonen fungerar alltid."
            r.controllerActive -> "Mottagaren är ansluten men styr inget just nu."
            r.pendingApproval -> "Någon har öppnat länken. Godkänn bara om du väntar dig det."
            r.link != null -> "Länken fungerar en gång och går ut efter 15 minuter."
            r.status == RemoteHost.Status.RECONNECTING -> r.error ?: ""
            else -> ""
        }
        link.visibility = if (r.link != null) View.VISIBLE else View.GONE
        link.text = r.link ?: ""
        shareRow.visibility = link.visibility
        approveRow.visibility = if (r.pendingApproval) View.VISIBLE else View.GONE
        val on = r.status != RemoteHost.Status.OFF && r.status != RemoteHost.Status.ERROR
        btnStart.text = if (on) "Ny fjärrlänk" else "Skapa fjärrlänk"
        btnUnlock.visibility = if (r.locked) View.VISIBLE else View.GONE
        refreshPumpNote()
        Ui.setEnabled(btnStop, on)
        Ui.setEnabled(btnKick, r.controllerActive || r.pendingApproval)
    }
}
