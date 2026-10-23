package com.hismith.session.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
    private val url: EditText = a.findViewById(R.id.rmUrl)
    private val secret: EditText = a.findViewById(R.id.rmSecret)
    private val swApproval: Switch = a.findViewById(R.id.rmApproval)
    private val swExtra: Switch = a.findViewById(R.id.rmAllowExtra)
    private val swSuper: Switch = a.findViewById(R.id.rmAllowSuper)
    private val seekMax: SeekBar = a.findViewById(R.id.rmMaxSpeed)
    private val valMax: TextView = a.findViewById(R.id.rmMaxSpeedVal)
    private val seekStop: SeekBar = a.findViewById(R.id.rmStopSec)
    private val valStop: TextView = a.findViewById(R.id.rmStopSecVal)
    private val seekRevoke: SeekBar = a.findViewById(R.id.rmRevokeSec)
    private val valRevoke: TextView = a.findViewById(R.id.rmRevokeSecVal)

    init {
        url.setText(settings.serverUrl)
        if (settings.hostSecretEncrypted != null) secret.hint = "Sparad (lämna tomt för att behålla)"
        val limits = settings.motionLimits
        swApproval.isChecked = settings.requireApproval
        swExtra.isChecked = limits.remoteAllowExtra
        swSuper.isChecked = limits.remoteAllowSuper
        seekMax.progress = limits.remoteMaxSpeed
        valMax.text = "${limits.remoteMaxSpeed} %"
        seekStop.progress = settings.failsafeStopSec - 2
        valStop.text = "${settings.failsafeStopSec} s"
        seekRevoke.progress = settings.failsafeRevokeSec - 3
        valRevoke.text = "${settings.failsafeRevokeSec} s"

        swApproval.setOnCheckedChangeListener { _, c -> settings.requireApproval = c }
        swExtra.setOnCheckedChangeListener { _, c -> settings.motionLimits = settings.motionLimits.copy(remoteAllowExtra = c); a.service?.settingsChanged() }
        swSuper.setOnCheckedChangeListener { _, c -> settings.motionLimits = settings.motionLimits.copy(remoteAllowSuper = c); a.service?.settingsChanged() }
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

        a.findViewById<Button>(R.id.btnRmSave).setOnClickListener { saveServer() }
        btnStart.setOnClickListener {
            val st = a.state ?: return@setOnClickListener
            if (settings.serverUrl.isBlank()) saveServer()
            if (settings.serverUrl.isBlank()) return@setOnClickListener Ui.toast(a, "Fyll i serveradressen först")
            if (st.remote.status == RemoteHost.Status.OFF || st.remote.status == RemoteHost.Status.ERROR) a.service?.remoteStart()
            else a.service?.remoteNewLink()
        }
        btnStop.setOnClickListener { a.service?.remoteStop() }
        btnKick.setOnClickListener { a.service?.remoteKick() }
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
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Länk", l))
            Ui.toast(a, "Länken kopierad")
        }
    }

    private fun saveServer() {
        val s = a.service
        val typed = url.text.toString().trim()
        if (typed.isNotEmpty()) {
            val norm = RemoteHost.normalize(typed)
            if (norm == null) return Ui.toast(a, "Adressen måste vara https://…")
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
        btnStart.text = if (on) "Ny länk" else "Skapa länk"
        Ui.setEnabled(btnStop, on)
        Ui.setEnabled(btnKick, r.controllerActive || r.pendingApproval)
    }
}
