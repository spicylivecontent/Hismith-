package com.hismith.session.ui

import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.hismith.session.DeviceRole
import com.hismith.session.MainActivity
import com.hismith.session.R
import com.hismith.session.UiState
import com.hismith.session.core.CommandArbiter
import com.hismith.session.core.DoseKind
import com.hismith.session.core.FlowModel
import com.hismith.session.core.LubeSettings
import com.hismith.session.core.MicroDoseSettings

/** Lube tab: launcher connection, doses, volumes/flow, calibration and maintenance. */
class LubeTab(private val a: MainActivity) {
    private val settings = a.settings

    private val status: TextView = a.findViewById(R.id.lubeStatus)
    private val firmware: TextView = a.findViewById(R.id.lubeFirmware)
    private val doseActive: TextView = a.findViewById(R.id.doseActive)
    private val doseProgress: ProgressBar = a.findViewById(R.id.doseProgress)
    private val seekDose: SeekBar = a.findViewById(R.id.seekDoseMl)
    private val valDose: TextView = a.findViewById(R.id.valDoseMl)
    private val btnDose: Button = a.findViewById(R.id.btnDose)
    private val btnDoseStop: Button = a.findViewById(R.id.btnDoseStop)
    private val btnExtra: Button = a.findViewById(R.id.btnLubeExtra)
    private val btnSuper: Button = a.findViewById(R.id.btnLubeSuper)
    private val totals: TextView = a.findViewById(R.id.doseTotals)

    private val inExtra: EditText = a.findViewById(R.id.inExtraMl)
    private val inSuper: EditText = a.findViewById(R.id.inSuperMl)
    private val inFinalMin: EditText = a.findViewById(R.id.inFinalMin)
    private val inFinalMax: EditText = a.findViewById(R.id.inFinalMax)
    private val inMoMl: EditText = a.findViewById(R.id.inMicroOralMl)
    private val inMoMin: EditText = a.findViewById(R.id.inMicroOralMin)
    private val inMoMax: EditText = a.findViewById(R.id.inMicroOralMax)
    private val inMaMl: EditText = a.findViewById(R.id.inMicroAnalMl)
    private val inMaMin: EditText = a.findViewById(R.id.inMicroAnalMin)
    private val inMaMax: EditText = a.findViewById(R.id.inMicroAnalMax)
    private val inFlowMicro: EditText = a.findViewById(R.id.inFlowMicro)
    private val inFlowNormal: EditText = a.findViewById(R.id.inFlowNormal)
    private val inFlowStrong: EditText = a.findViewById(R.id.inFlowStrong)
    private val inMaxPump: EditText = a.findViewById(R.id.inMaxPump)
    private val inMaxSession: EditText = a.findViewById(R.id.inMaxSession)
    private val preview: TextView = a.findViewById(R.id.lubePreview)
    private val swPulsedFinal: Switch = a.findViewById(R.id.swPulsedFinal)
    private val swPulsedSuper: Switch = a.findViewById(R.id.swPulsedSuper)
    private val seekPulses: SeekBar = a.findViewById(R.id.seekPulses)
    private val valPulses: TextView = a.findViewById(R.id.valPulses)

    private val calStatus: TextView = a.findViewById(R.id.calStatus)
    private val seekCalSpeed: SeekBar = a.findViewById(R.id.seekCalSpeed)
    private val valCalSpeed: TextView = a.findViewById(R.id.valCalSpeed)
    private val seekCalSeconds: SeekBar = a.findViewById(R.id.seekCalSeconds)
    private val valCalSeconds: TextView = a.findViewById(R.id.valCalSeconds)
    private val btnCalRun: Button = a.findViewById(R.id.btnCalRun)
    private val inCalMeasured: EditText = a.findViewById(R.id.inCalMeasured)
    private val btnCalSave: Button = a.findViewById(R.id.btnCalSave)
    private val btnCalReset: Button = a.findViewById(R.id.btnCalReset)
    private val btnPrime: Button = a.findViewById(R.id.btnPrime)
    private val btnRetract: Button = a.findViewById(R.id.btnRetract)

    private val calSpeed: Int get() = 10 + seekCalSpeed.progress * 5
    private val calSeconds: Int get() = 3 + seekCalSeconds.progress
    private var lastCalRun: Pair<Int, Int>? = null

    init {
        loadSettings()
        seekDose.onChange({ v, _ -> valDose.text = "${v + 1} ml" })
        seekDose.progress = (settings.lubeSettings.manualMl.toInt() - 1).coerceIn(0, 29)
        valDose.text = "${seekDose.progress + 1} ml"

        a.findViewById<Button>(R.id.btnConnectMega).setOnClickListener { a.devicePicker.pickMegaLube() }
        a.findViewById<Button>(R.id.btnLubeMore).setOnClickListener {
            Ui.dialog(a)
                .setTitle("Annan launcher")
                .setItems(arrayOf("Simulerad MegaLube (test utan hårdvara)", "Hismith Lube Launcher (BLE)")) { _, which ->
                    if (which == 0) a.service?.addSimulatedLube() else a.devicePicker.scanBle(DeviceRole.LUBE_HISMITH)
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }
        btnDose.setOnClickListener {
            val ml = (seekDose.progress + 1).toDouble()
            settings.lubeSettings = settings.lubeSettings.copy(manualMl = ml)
            a.service?.dose(DoseKind.MANUAL, ml)
        }
        btnDoseStop.setOnClickListener { a.service?.stopDose() }
        btnExtra.setOnClickListener { a.service?.dose(DoseKind.EXTRA, null) }
        btnSuper.setOnClickListener { a.service?.dose(DoseKind.SUPER, null) }
        a.findViewById<Button>(R.id.btnReservoir).setOnClickListener { reservoirDialog() }
        a.findViewById<Button>(R.id.btnSaveLube).setOnClickListener { saveSettings() }
        swPulsedFinal.setOnCheckedChangeListener { _, c -> settings.lubeSettings = settings.lubeSettings.copy(pulsedFinal = c) }
        swPulsedSuper.setOnCheckedChangeListener { _, c -> settings.lubeSettings = settings.lubeSettings.copy(pulsedSuper = c) }
        seekPulses.onChange({ v, _ -> valPulses.text = "${v + 2} st" }, onStop = { v ->
            settings.lubeSettings = settings.lubeSettings.copy(pulseCount = v + 2)
        })

        seekCalSpeed.onChange({ _, _ -> valCalSpeed.text = "$calSpeed %" })
        seekCalSeconds.onChange({ _, _ -> valCalSeconds.text = "$calSeconds s" })
        seekCalSpeed.progress = 8 // 50 %
        seekCalSeconds.progress = 7 // 10 s
        valCalSpeed.text = "$calSpeed %"
        valCalSeconds.text = "$calSeconds s"
        btnCalRun.setOnClickListener {
            val sp = calSpeed
            val sec = calSeconds
            Ui.dialog(a)
                .setTitle("Kör kalibrering")
                .setMessage("Pumpen kör $sp % i $sec sekunder. Håll slangens ände i ett måttglas och fyll i hur många ml som kom ut.")
                .setPositiveButton("Kör") { _, _ ->
                    lastCalRun = sp to sec
                    a.service?.serviceRun(sp, sec)
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }
        btnCalSave.setOnClickListener {
            val ml = Ui.parseDouble(inCalMeasured.text)
            val run = lastCalRun ?: (calSpeed to calSeconds)
            if (ml == null || ml <= 0) return@setOnClickListener Ui.toast(a, "Fyll i uppmätt mängd i ml")
            a.service?.saveCalibrationPoint(run.first, run.second, ml)
            inCalMeasured.setText("")
            Ui.toast(a, "Sparat: ${run.first} % i ${run.second} s = ${Ui.ml(ml)}")
        }
        btnCalReset.setOnClickListener {
            Ui.dialog(a)
                .setTitle("Återställ kalibrering?")
                .setMessage("Alla mätpunkter för den här launchern tas bort. Doserna blir uppskattningar tills du kalibrerar igen.")
                .setPositiveButton("Återställ") { _, _ -> a.service?.resetCalibration() }
                .setNegativeButton("Avbryt", null)
                .show()
        }
        btnPrime.setOnClickListener { a.service?.serviceRun(40, 5) }
        btnRetract.setOnClickListener {
            Ui.dialog(a)
                .setTitle("Töm slangen bakåt?")
                .setMessage("Pumpen kör BAKÅT i 8 sekunder och suger tillbaka gelen till dunken.\n\nTa bort leksaken från kroppen först – bakåt blir det sug.")
                .setPositiveButton("Kör bakåt") { _, _ -> a.service?.serviceRun(-50, 8) }
                .setNegativeButton("Avbryt", null)
                .show()
        }
    }

    private fun loadSettings() {
        val s = settings.lubeSettings
        inExtra.setText(Ui.num(s.extraMl))
        inSuper.setText(Ui.num(s.superMl))
        inFinalMin.setText(Ui.num(s.finalMinMl))
        inFinalMax.setText(Ui.num(s.finalMaxMl))
        inMoMl.setText(Ui.num(s.microOral.ml, 2))
        inMoMin.setText(s.microOral.intervalMinSec.toString())
        inMoMax.setText(s.microOral.intervalMaxSec.toString())
        inMaMl.setText(Ui.num(s.microAnal.ml, 2))
        inMaMin.setText(s.microAnal.intervalMinSec.toString())
        inMaMax.setText(s.microAnal.intervalMaxSec.toString())
        inFlowMicro.setText(Ui.num(s.flowMicro, 2))
        inFlowNormal.setText(Ui.num(s.flowNormal, 2))
        inFlowStrong.setText(Ui.num(s.flowStrong, 2))
        inMaxPump.setText(s.maxPumpSpeed.toString())
        inMaxSession.setText(Ui.num(s.maxSessionMl, 0))
        swPulsedFinal.isChecked = s.pulsedFinal
        swPulsedSuper.isChecked = s.pulsedSuper
        seekPulses.progress = s.pulseCount - 2
        valPulses.text = "${s.pulseCount} st"
    }

    private fun saveSettings() {
        val old = settings.lubeSettings
        val new = LubeSettings(
            extraMl = inExtra.doubleOr(old.extraMl),
            superMl = inSuper.doubleOr(old.superMl),
            manualMl = old.manualMl,
            finalMinMl = inFinalMin.doubleOr(old.finalMinMl),
            finalMaxMl = inFinalMax.doubleOr(old.finalMaxMl),
            microOral = MicroDoseSettings(inMoMl.doubleOr(old.microOral.ml), inMoMin.intOr(old.microOral.intervalMinSec), inMoMax.intOr(old.microOral.intervalMaxSec)),
            microAnal = MicroDoseSettings(inMaMl.doubleOr(old.microAnal.ml), inMaMin.intOr(old.microAnal.intervalMinSec), inMaMax.intOr(old.microAnal.intervalMaxSec)),
            flowMicro = inFlowMicro.doubleOr(old.flowMicro),
            flowNormal = inFlowNormal.doubleOr(old.flowNormal),
            flowStrong = inFlowStrong.doubleOr(old.flowStrong),
            maxPumpSpeed = inMaxPump.intOr(old.maxPumpSpeed),
            maxSessionMl = inMaxSession.doubleOr(old.maxSessionMl),
            pulsedFinal = swPulsedFinal.isChecked,
            pulsedSuper = swPulsedSuper.isChecked,
            pulseCount = seekPulses.progress + 2,
        ).sanitized()
        settings.lubeSettings = new
        loadSettings()
        a.service?.settingsChanged()
        Ui.toast(a, "Lube-inställningar sparade")
    }

    private fun reservoirDialog() {
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "ml i dunken, t.ex. 5000"
            setText(settings.reservoirMl?.let { Ui.num(it, 0) } ?: "")
            setTextColor(a.getColor(R.color.text))
            setPadding(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 20), Ui.dp(a, 12))
        }
        Ui.dialog(a)
            .setTitle("Hur mycket finns i dunken?")
            .setMessage("Appen drar av varje dos och varnar när det börjar ta slut.")
            .setView(input)
            .setPositiveButton("Spara") { _, _ -> a.service?.setReservoir(Ui.parseDouble(input.text)) }
            .setNeutralButton("Sluta räkna") { _, _ -> a.service?.setReservoir(null) }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun calibrationFor(st: UiState): FlowModel =
        settings.calibration(st.lube.role ?: DeviceRole.LUBE_MEGALUBE)

    fun render(st: UiState) {
        val l = st.lube
        status.text = when {
            !l.connected -> "Ingen launcher ansluten"
            l.calibrated -> "${l.name} · kalibrerad"
            else -> "${l.name} · OKALIBRERAD – doserna är uppskattningar"
        }
        status.setTextColor(a.getColor(if (l.connected && !l.calibrated) R.color.amber else R.color.text))
        firmware.text = buildString {
            l.firmware?.let { append(it) }
            l.linkAgeMs?.let { if (it > 3_000) append("\nInget svar från kortet på ${it / 1000} s") }
        }

        val active = l.active
        if (active != null) {
            doseActive.text = "${active.kind.label}: ${Ui.ml(active.deliveredMl)} av ${Ui.ml(active.ml)} · ${active.speedPct} % · " +
                "${(active.remainingMs + 999) / 1000} s kvar" +
                (if (active.segments > 1) " · del ${active.segment}/${active.segments}" else "") +
                (if (l.queued.isNotEmpty()) "\nI kö: ${l.queued.joinToString { it.label }}" else "")
            doseProgress.progress = if (active.ml > 0) (active.deliveredMl / active.ml * 1000).toInt() else 0
        } else {
            doseActive.text = if (st.mode.lubeAllowed) "Ingen dos pågår" else "Pocket-läge: ingen lube"
            doseProgress.progress = 0
        }

        val lubeOk = l.connected && st.mode.lubeAllowed
        Ui.setEnabled(btnDose, lubeOk)
        Ui.setEnabled(btnExtra, lubeOk)
        Ui.setEnabled(btnSuper, lubeOk)
        Ui.setEnabled(btnDoseStop, active != null)
        val s = settings.lubeSettings
        btnExtra.text = "EXTRA ${Ui.ml(s.extraMl)}"
        btnSuper.text = "SUPER ${Ui.ml(s.superMl)}"

        totals.text = buildString {
            append("Denna session: ${Ui.ml(l.sessionMl)} av max ${Ui.ml(s.maxSessionMl)}")
            append("\nDunken: ")
            append(l.reservoirMl?.let { if (it < 200) "${Ui.ml(it)} kvar – fyll på snart!" else "${Ui.ml(it)} kvar" } ?: "räknas inte")
        }

        val idle = st.control == CommandArbiter.Control.NONE && active == null
        Ui.setEnabled(btnCalRun, l.connected && idle)
        Ui.setEnabled(btnPrime, l.connected && idle)
        Ui.setEnabled(btnRetract, l.connected && idle && l.supportsReverse)

        val cal = calibrationFor(st)
        calStatus.text = if (!cal.calibrated) {
            "Okalibrerad – appen antar ${Ui.num(FlowModel.DEFAULT.flowAt(100))} ml/s vid 100 %."
        } else {
            cal.points.joinToString("\n") { "${it.speedPct} %  →  ${Ui.num(it.mlPerSec, 2)} ml/s  (${Ui.num(it.mlPerSec * 60, 0)} ml/min)" }
        }

        preview.text = listOf(
            DoseKind.MICRO to s.microFor(st.mode).ml,
            DoseKind.EXTRA to s.extraMl,
            DoseKind.SUPER to s.superMl,
        ).joinToString("\n") { (kind, ml) ->
            val pulsed = kind == DoseKind.SUPER && s.pulsedSuper
            val plan = if (pulsed) cal.planPulsed(s.clampMl(kind, ml), s.flowFor(kind), s.maxPumpSpeed, s.pulseCount)
            else cal.plan(s.clampMl(kind, ml), s.flowFor(kind), s.maxPumpSpeed)
            if (plan == null) "${kind.label}: kan inte planeras"
            else "${kind.label} ${Ui.ml(plan.ml)} ≈ ${Ui.num(plan.totalMs / 1000.0)} s vid ${plan.speedPct} % (${Ui.num(plan.mlPerSec, 2)} ml/s)" +
                (if (pulsed) " i ${plan.segmentsMs.size} stötar" else "")
        }
    }
}
