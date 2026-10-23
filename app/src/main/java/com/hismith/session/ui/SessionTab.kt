package com.hismith.session.ui

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.hismith.session.DeviceKind
import com.hismith.session.DeviceRole
import com.hismith.session.MainActivity
import com.hismith.session.R
import com.hismith.session.UiState
import com.hismith.session.core.CommandArbiter
import com.hismith.session.core.DoseKind
import com.hismith.session.core.SessionMode
import com.hismith.session.core.Source

/** Session tab: live view, devices, mode, automatic session, manual control and limits. */
class SessionTab(private val a: MainActivity) {
    private val settings = a.settings

    private val time: TextView = a.findViewById(R.id.sessTime)
    private val remaining: TextView = a.findViewById(R.id.sessRemaining)
    private val speed: TextView = a.findViewById(R.id.sessSpeed)
    private val phase: TextView = a.findViewById(R.id.sessPhase)
    private val graph: SpeedGraphView = a.findViewById(R.id.speedGraph)
    private val progress: ProgressBar = a.findViewById(R.id.sessProgress)
    private val btnPause: Button = a.findViewById(R.id.btnPause)
    private val btnFinalNow: Button = a.findViewById(R.id.btnFinalNow)
    private val btnEnd: Button = a.findViewById(R.id.btnEnd)
    private val btnExtra: Button = a.findViewById(R.id.btnExtra)
    private val btnSuper: Button = a.findViewById(R.id.btnSuper)

    private val devicesList: LinearLayout = a.findViewById(R.id.devicesList)
    private val devicesEmpty: TextView = a.findViewById(R.id.devicesEmpty)

    private val modeViews = mapOf(
        SessionMode.ORAL to a.findViewById<TextView>(R.id.modeOral),
        SessionMode.ANAL to a.findViewById<TextView>(R.id.modeAnal),
        SessionMode.POCKET to a.findViewById<TextView>(R.id.modePocket),
    )
    private val modeHint: TextView = a.findViewById(R.id.modeHint)

    private val inMin: EditText = a.findViewById(R.id.inMinTime)
    private val inMax: EditText = a.findViewById(R.id.inMaxTime)
    private val seekIntensity: SeekBar = a.findViewById(R.id.seekIntensity)
    private val valIntensity: TextView = a.findViewById(R.id.valIntensity)
    private val swAutoLube: Switch = a.findViewById(R.id.swAutoLube)
    private val swFinal: Switch = a.findViewById(R.id.swFinal)
    private val swSmartStroke: Switch = a.findViewById(R.id.swSmartStroke)
    private val swNonstop: Switch = a.findViewById(R.id.swNonstop)
    private val btnStart: Button = a.findViewById(R.id.btnStart)
    private val startHint: TextView = a.findViewById(R.id.startHint)

    private val btnManual: Button = a.findViewById(R.id.btnManual)
    private val manualControls: View = a.findViewById(R.id.manualControls)
    private val seekManual: SeekBar = a.findViewById(R.id.seekManual)
    private val valManual: TextView = a.findViewById(R.id.valManual)
    private val seekStrokeStart: SeekBar = a.findViewById(R.id.seekStrokeStart)
    private val valStrokeStart: TextView = a.findViewById(R.id.valStrokeStart)
    private val seekStrokeStop: SeekBar = a.findViewById(R.id.seekStrokeStop)
    private val valStrokeStop: TextView = a.findViewById(R.id.valStrokeStop)

    private val seekMaxOral: SeekBar = a.findViewById(R.id.seekMaxOral)
    private val seekMaxAnal: SeekBar = a.findViewById(R.id.seekMaxAnal)
    private val seekMaxPocket: SeekBar = a.findViewById(R.id.seekMaxPocket)
    private val valMaxOral: TextView = a.findViewById(R.id.valMaxOral)
    private val valMaxAnal: TextView = a.findViewById(R.id.valMaxAnal)
    private val valMaxPocket: TextView = a.findViewById(R.id.valMaxPocket)
    private val swAutoReconnect: Switch = a.findViewById(R.id.swAutoReconnect)
    private val eventsText: TextView = a.findViewById(R.id.eventsText)

    private var lastDeviceKey = ""
    private var draggingManual = false

    init {
        loadSettings()
        modeViews.forEach { (mode, v) ->
            v.setOnClickListener {
                if (a.state?.session?.running == true) {
                    Ui.toast(a, "Läget kan inte bytas under en session")
                } else {
                    a.service?.setMode(mode)
                    showMode(mode)
                }
            }
        }
        seekIntensity.onChange({ v, fromUser ->
            valIntensity.text = "$v %"
            if (fromUser && a.state?.session?.running == true) a.service?.setIntensity(v)
        }, onStop = { settings.intensity = it })
        swAutoLube.setOnCheckedChangeListener { _, c -> settings.autoLube = c }
        swFinal.setOnCheckedChangeListener { _, c -> settings.finalEnabled = c }
        swSmartStroke.setOnCheckedChangeListener { _, c -> settings.smartStroke = c }
        swNonstop.setOnCheckedChangeListener { _, c -> settings.nonstop = c }
        swAutoReconnect.setOnCheckedChangeListener { _, c -> settings.autoReconnect = c }

        btnStart.setOnClickListener {
            saveTimes()
            val s = a.service ?: return@setOnClickListener Ui.toast(a, "Tjänsten startar – försök igen")
            s.startSession(s.buildConfigFromSettings())
        }
        btnPause.setOnClickListener {
            val st = a.state ?: return@setOnClickListener
            if (st.session.paused) a.service?.resumeSession() else a.service?.pauseSession()
        }
        btnFinalNow.setOnClickListener { a.service?.finalNow() }
        btnEnd.setOnClickListener {
            val st = a.state ?: return@setOnClickListener
            if (st.session.running) a.service?.endSession() else a.service?.manualEnd()
        }
        btnExtra.setOnClickListener { a.service?.dose(DoseKind.EXTRA, null) }
        btnSuper.setOnClickListener { a.service?.dose(DoseKind.SUPER, null) }

        a.findViewById<Button>(R.id.btnAddMachine).setOnClickListener { a.devicePicker.addMotionDevice() }
        a.findViewById<Button>(R.id.btnAddSim).setOnClickListener {
            a.service?.addSimulatedMachine()
            Ui.toast(a, "Simulerad maskin tillagd")
        }

        btnManual.setOnClickListener {
            val st = a.state ?: return@setOnClickListener
            if (st.control == CommandArbiter.Control.MANUAL && st.owner == Source.LOCAL) {
                a.service?.manualEnd()
            } else {
                seekManual.progress = 0
                a.service?.manualStart()
            }
        }
        seekManual.onChange({ v, fromUser ->
            valManual.text = "$v %"
            if (fromUser) {
                draggingManual = true
                a.service?.manualSpeed(v)
            }
        }, onStop = { draggingManual = false })
        seekStrokeStart.onChange({ v, _ -> valStrokeStart.text = "$v" }, onStop = { sendStroke() })
        seekStrokeStop.onChange({ v, _ -> valStrokeStop.text = "$v" }, onStop = { sendStroke() })
        seekStrokeStart.progress = 15
        seekStrokeStop.progress = 85

        val limits = settings.motionLimits
        bindLimit(seekMaxOral, valMaxOral, limits.maxSpeedOral) { l, v -> l.copy(maxSpeedOral = v) }
        bindLimit(seekMaxAnal, valMaxAnal, limits.maxSpeedAnal) { l, v -> l.copy(maxSpeedAnal = v) }
        bindLimit(seekMaxPocket, valMaxPocket, limits.maxSpeedPocket) { l, v -> l.copy(maxSpeedPocket = v) }
    }

    private fun bindLimit(
        seek: SeekBar,
        value: TextView,
        initial: Int,
        update: (com.hismith.session.core.MotionLimits, Int) -> com.hismith.session.core.MotionLimits,
    ) {
        seek.progress = initial
        value.text = "$initial %"
        seek.onChange({ v, _ -> value.text = "$v %" }, onStop = { v ->
            settings.motionLimits = update(settings.motionLimits, v.coerceAtLeast(10))
            if (v < 10) seek.progress = 10
            a.service?.settingsChanged()
        })
    }

    private fun sendStroke() {
        val start = seekStrokeStart.progress
        val stop = seekStrokeStop.progress.coerceAtLeast(start + 10)
        a.service?.manualStroke(start, stop)
    }

    private fun loadSettings() {
        inMin.setText(settings.minMinutes.toString())
        inMax.setText(settings.maxMinutes.toString())
        seekIntensity.progress = settings.intensity
        valIntensity.text = "${settings.intensity} %"
        swAutoLube.isChecked = settings.autoLube
        swFinal.isChecked = settings.finalEnabled
        swSmartStroke.isChecked = settings.smartStroke
        swNonstop.isChecked = settings.nonstop
        swAutoReconnect.isChecked = settings.autoReconnect
        showMode(settings.mode)
    }

    fun saveTimes() {
        val min = inMin.intOr(20).coerceIn(1, 240)
        val max = inMax.intOr(45).coerceIn(0, 240)
        settings.minMinutes = min
        settings.maxMinutes = if (max == 0) 0 else maxOf(min, max)
        inMin.setText(settings.minMinutes.toString())
        inMax.setText(settings.maxMinutes.toString())
    }

    private fun showMode(mode: SessionMode) {
        modeViews.forEach { (m, v) ->
            v.isSelected = m == mode
            v.setTextColor(a.getColor(if (m == mode) R.color.gold_pale else R.color.muted))
        }
        modeHint.text = when (mode) {
            SessionMode.ORAL -> "Oral: mikrodoser enligt Oral-inställningen. Maxhastighet ${settings.motionLimits.maxSpeedOral} %."
            SessionMode.ANAL -> "Anal: mikrodoser enligt Anal-inställningen. Maxhastighet ${settings.motionLimits.maxSpeedAnal} %."
            SessionMode.POCKET -> "Pocket: ingen lube alls – alla lube-kommandon nekas, även från länken."
        }
    }

    fun render(st: UiState) {
        val s = st.session
        val manual = st.control == CommandArbiter.Control.MANUAL
        time.text = Ui.clock(s.elapsedSec)
        remaining.text = when {
            s.running && s.remainingSec != null -> "kvar ${Ui.clock(s.remainingSec)}" +
                (if (s.finalMl > 0 && !s.finalStarted) " · final ${Ui.ml(s.finalMl)}" else "") +
                (s.challengeDay?.let { " · dag $it" } ?: "")
            s.running -> "obegränsad tid" + (s.challengeDay?.let { " · dag $it" } ?: "")
            manual -> "manuell styrning (${st.owner?.label ?: ""})"
            else -> "ingen session"
        }
        speed.text = "${st.speed} %"
        phase.text = when {
            st.safety == CommandArbiter.Safety.STOPPED -> "STOPPAD"
            s.running || s.paused -> s.label
            manual && st.safety == CommandArbiter.Safety.PAUSED -> "Pausad – ${st.safetyReason}"
            manual -> "Manuell · mål ${st.manualTarget} %"
            else -> if (st.anyMotionConnected) "Redo" else "Anslut en maskin för att börja"
        }
        phase.setTextColor(a.getColor(if (st.safety == CommandArbiter.Safety.STOPPED) R.color.red else R.color.text))
        graph.setData(st.speedHistory)
        progress.progress = (s.progress * 1000).toInt()

        val active = s.running || manual
        btnPause.text = if (s.paused) "Fortsätt" else "Pausa"
        Ui.setEnabled(btnPause, active)
        Ui.setEnabled(btnFinalNow, s.running && !s.finalStarted && !s.paused)
        Ui.setEnabled(btnEnd, active)

        val lubeOk = st.lube.connected && st.mode.lubeAllowed
        val ls = settings.lubeSettings
        btnExtra.text = "EXTRA ${Ui.ml(ls.extraMl)}"
        btnSuper.text = "SUPER ${Ui.ml(ls.superMl)}"
        Ui.setEnabled(btnExtra, lubeOk)
        Ui.setEnabled(btnSuper, lubeOk)

        renderDevices(st)
        showMode(st.mode)
        modeViews.values.forEach { Ui.setEnabled(it, !s.running) }

        Ui.setEnabled(btnStart, st.anyMotionConnected && !s.running && !manual)
        startHint.text = when {
            !st.anyMotionConnected -> "Ingen maskin ansluten"
            s.running -> "Session pågår"
            manual -> "Avsluta manuell styrning först"
            st.safety == CommandArbiter.Safety.STOPPED -> "Stoppad: ${st.safetyReason}. Start kvitterar stoppet."
            !st.lube.connected && st.mode.lubeAllowed -> "Ingen launcher ansluten – passet körs utan lube"
            else -> ""
        }

        val localManual = manual && st.owner == Source.LOCAL
        btnManual.text = when {
            localManual -> "Avsluta manuell styrning"
            manual -> "Länken styr manuellt"
            else -> "Starta manuell styrning"
        }
        Ui.setEnabled(btnManual, localManual || (!manual && !s.running && st.anyMotionConnected))
        manualControls.visibility = if (localManual) View.VISIBLE else View.GONE
        if (localManual && !draggingManual && seekManual.progress != st.manualTarget) {
            seekManual.progress = st.manualTarget
        }

        eventsText.text = if (st.events.isEmpty()) "Inga händelser än" else st.events.take(14).joinToString("\n")
    }

    private fun renderDevices(st: UiState) {
        val key = st.devices.joinToString("|") { "${it.id}:${it.connected}:${it.detail}" }
        if (key == lastDeviceKey) return
        lastDeviceKey = key
        devicesList.removeAllViews()
        devicesEmpty.visibility = if (st.devices.isEmpty()) View.VISIBLE else View.GONE
        for (d in st.devices) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6))
            }
            val dot = View(a).apply {
                setBackgroundResource(R.drawable.dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    a.getColor(if (d.connected) R.color.green else R.color.amber)
                )
            }
            row.addView(dot, LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10)).apply { rightMargin = Ui.dp(a, 10) })
            val text = TextView(a).apply {
                val kind = when (d.kind) {
                    DeviceKind.SIMULATED -> " · simulator"
                    DeviceKind.SPP -> " · Bluetooth"
                    DeviceKind.BLE -> " · BLE"
                }
                this.text = "${d.name}\n${d.role.label}$kind · ${d.detail}"
                setTextColor(a.getColor(R.color.text))
                textSize = 13f
            }
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val remove = Button(a, null, 0, R.style.Btn_Text).apply {
                this.text = "✕"
                setTextColor(a.getColor(R.color.muted))
                setOnClickListener {
                    Ui.dialog(a)
                        .setTitle("Ta bort ${d.name}?")
                        .setMessage(if (d.role == DeviceRole.MACHINE || d.role == DeviceRole.POCKET_STROKER)
                            "Maskinen stoppas och appen slutar ansluta till den." else "Appen slutar ansluta till enheten.")
                        .setPositiveButton("Ta bort") { _, _ -> a.service?.removeDevice(d.id) }
                        .setNegativeButton("Avbryt", null)
                        .show()
                }
            }
            row.addView(remove, LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 40)))
            devicesList.addView(row)
        }
    }
}
