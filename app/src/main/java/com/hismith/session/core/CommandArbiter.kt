package com.hismith.session.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** The physical motion outputs (all connected machines). Implemented by the service. */
interface MotionOutputs {
    fun setSpeed(percent: Int)
    fun setStroke(startPercent: Int, stopPercent: Int)
    /** Speed 0 + the machine's own stop/power-off commands. Must be safe to call repeatedly. */
    fun stopMotion()
}

/** Speed limits. Auto output is scaled into the limit, manual/remote is clamped to it. */
data class MotionLimits(
    val maxSpeedOral: Int = 75,
    val maxSpeedAnal: Int = 100,
    val maxSpeedPocket: Int = 100,
    /** Extra cap for the remote controller (never above the mode limit). */
    val remoteMaxSpeed: Int = 100,
    /** The remote may spray with the Lube Launcher (never in Pocket mode). */
    val remoteAllowLube: Boolean = true,
    /** Most the remote may spray per click (ml, never above [Limits.MANUAL_MAX_ML]). */
    val remoteMaxMl: Int = 30,
    /** The remote may start automatic sessions. Off: the link is a separate manual mode. */
    val remoteAllowAuto: Boolean = false,
    /** Manual / remote speed changes are slewed at this rate (%/s) – no hard jumps. */
    val slewPerSecond: Int = 60,
) {
    fun maxFor(mode: SessionMode): Int = when (mode) {
        SessionMode.ORAL -> maxSpeedOral
        SessionMode.ANAL -> maxSpeedAnal
        SessionMode.POCKET -> maxSpeedPocket
    }.coerceIn(0, 100)
}

/**
 * The only component allowed to drive motors and the pump. Automatic engine, local UI, remote
 * link and challenges all request through here, so the same limits, STOP rules, comm-loss
 * handling and mode rules apply whoever is asking.
 *
 * Rules:
 *  - STOP is idempotent, always allowed and wins over everything; afterwards motion stays off
 *    until somebody explicitly starts again (no automatic restart).
 *  - Only the current owner of motion may change speed.
 *  - Pocket mode refuses every lube command, whatever the source.
 *  - The remote can never exceed the local limits; it only sprays plain doses, and only
 *    starts automatic sessions when the owner allows it.
 */
class CommandArbiter(
    private val out: MotionOutputs,
    private val doses: DoseController,
    var limits: MotionLimits = MotionLimits(),
) {
    enum class Control { NONE, AUTO, MANUAL }
    enum class Safety { IDLE, ACTIVE, PAUSED, STOPPED }

    var mode: SessionMode = SessionMode.ANAL
        private set
    var control = Control.NONE
        private set
    var owner: Source? = null
        private set
    var safety = Safety.IDLE
        private set
    var reason: String = ""
        private set

    /** Last speed actually sent to the machines (after limits). */
    var outputSpeed = 0
        private set
    private var manualTarget = 0.0
    private var manualCurrent = 0.0
    private var lastTick = -1L
    private var lastStroke: Pair<Int, Int>? = null

    val isMotionActive: Boolean get() = control != Control.NONE && safety == Safety.ACTIVE

    fun setMode(m: SessionMode) {
        mode = m
        if (!m.lubeAllowed) doses.stopAll("Pocket-läge: ingen lube")
        // Re-apply limits on the next manual tick / auto frame.
    }

    private fun limitFor(source: Source?): Int {
        val modeMax = limits.maxFor(mode)
        return if (source == Source.REMOTE) minOf(modeMax, limits.remoteMaxSpeed.coerceIn(0, 100)) else modeMax
    }

    /** Explicit start of an automatic session. Clears a previous STOP. */
    fun beginAuto(source: Source): Boolean {
        if (source == Source.REMOTE && !limits.remoteAllowAuto) return false
        control = Control.AUTO
        owner = source
        safety = Safety.ACTIVE
        reason = ""
        manualTarget = 0.0
        manualCurrent = 0.0
        return true
    }

    /** Explicit start of manual control (local slider or the remote). */
    fun beginManual(source: Source): Boolean {
        if (control == Control.AUTO && owner != source && source == Source.REMOTE) return false
        control = Control.MANUAL
        owner = source
        safety = Safety.ACTIVE
        reason = ""
        manualTarget = 0.0
        manualCurrent = outputSpeed.toDouble()
        return true
    }

    /** Hand back control without an emergency (session ended normally, manual off). */
    fun endControl(source: Source) {
        if (control == Control.NONE) return
        if (source != Source.LOCAL && source != Source.SYSTEM && owner != source) return
        control = Control.NONE
        owner = null
        if (safety != Safety.STOPPED) safety = Safety.IDLE
        manualTarget = 0.0
        manualCurrent = 0.0
        sendSpeed(0)
        out.stopMotion()
    }

    /** Engine output while the automatic session owns the motion. */
    fun applyAuto(frame: EngineFrame, smartStroke: Boolean) {
        if (control != Control.AUTO || safety != Safety.ACTIVE) return
        val scaled = (frame.speed.coerceIn(0, 100) * limitFor(owner) / 100.0).roundToInt()
        sendSpeed(scaled)
        if (smartStroke) sendStroke(frame.strokeStart, frame.strokeStop)
    }

    /** Manual speed from the owner. Returns false when refused. */
    fun manualSpeed(source: Source, percent: Int): Boolean {
        if (control != Control.MANUAL || safety != Safety.ACTIVE || owner != source) return false
        manualTarget = percent.coerceIn(0, limitFor(source)).toDouble()
        return true
    }

    fun manualStroke(source: Source, start: Int, stop: Int): Boolean {
        if (control == Control.NONE || safety != Safety.ACTIVE || owner != source) return false
        sendStroke(start, stop)
        return true
    }

    val manualTargetPercent: Int get() = manualTarget.roundToInt()

    /** Slew limiter for manual / remote control. Call every control tick. */
    fun tick(now: Long) {
        val dt = if (lastTick < 0) 0L else (now - lastTick).coerceIn(0, 500)
        lastTick = now
        if (control != Control.MANUAL || safety != Safety.ACTIVE) return
        val limit = limitFor(owner).toDouble()
        val target = manualTarget.coerceAtMost(limit)
        val step = limits.slewPerSecond.coerceIn(5, 400) * dt / 1000.0
        manualCurrent = when {
            abs(target - manualCurrent) <= step -> target
            target > manualCurrent -> manualCurrent + step
            else -> manualCurrent - step
        }
        sendSpeed(manualCurrent.roundToInt())
    }

    /** Pause (e.g. a machine dropped). Motion to 0, running dose stopped, owner kept. */
    fun pause(why: String) {
        if (safety != Safety.ACTIVE) return
        safety = Safety.PAUSED
        reason = why
        manualTarget = 0.0
        manualCurrent = 0.0
        sendSpeed(0)
        doses.stopAll(why)
    }

    /** Resume after a pause. Manual control restarts at 0 – the owner must raise it again. */
    fun resume(source: Source): Boolean {
        if (safety != Safety.PAUSED) return false
        if (source == Source.REMOTE && owner != Source.REMOTE) return false
        safety = Safety.ACTIVE
        reason = ""
        return true
    }

    /** STOP: idempotent, highest priority. */
    fun stop(source: Source, why: String) {
        control = Control.NONE
        owner = null
        safety = Safety.STOPPED
        reason = if (source == Source.SYSTEM) why else "${source.label}: $why"
        manualTarget = 0.0
        manualCurrent = 0.0
        outputSpeed = 0
        lastStroke = null
        doses.stopAll(why)
        out.stopMotion()
    }

    /** Acknowledge a STOP without starting anything (back to idle). */
    fun clearStop() {
        if (safety == Safety.STOPPED) {
            safety = Safety.IDLE
            reason = ""
        }
    }

    /** [pumpPct]: pump speed in % of the safe max instead of the configured flow (remote spray). */
    fun requestDose(source: Source, kind: DoseKind, ml: Double, now: Long, pumpPct: Int? = null): DoseResult {
        if (!mode.lubeAllowed) return DoseResult.Rejected("Pocket-läge: ingen lube")
        var volume = ml
        if (source == Source.REMOTE) {
            // The remote only knows "spray X ml": a plain dose within the owner's cap per click.
            if (kind != DoseKind.MANUAL || !limits.remoteAllowLube) return DoseResult.Rejected("Inte tillåtet via länken")
            volume = ml.coerceAtMost(limits.remoteMaxMl.coerceIn(1, Limits.MANUAL_MAX_ML.toInt()).toDouble())
        }
        if (source != Source.LOCAL && safety == Safety.STOPPED) return DoseResult.Rejected("Stoppad")
        if (source == Source.AUTO && (control != Control.AUTO || safety != Safety.ACTIVE)) {
            return DoseResult.Rejected("Ingen aktiv session")
        }
        if (kind == DoseKind.SERVICE && (control != Control.NONE || source != Source.LOCAL)) {
            return DoseResult.Rejected("Underhåll bara när ingen session körs")
        }
        return doses.submit(DoseRequest(kind, volume, source, pumpPct = pumpPct), now)
    }

    /** Pump service run (prime / empty / calibrate). Local only, only while idle; allowed in Pocket. */
    fun requestService(speed: Int, ms: Long, now: Long): DoseResult {
        if (control != Control.NONE) return DoseResult.Rejected("Underhåll bara när ingen session körs")
        return doses.submit(DoseRequest(DoseKind.SERVICE, 0.0, Source.LOCAL, speed, ms), now)
    }

    private fun sendSpeed(p: Int) {
        val v = p.coerceIn(0, 100)
        outputSpeed = v
        out.setSpeed(v)
    }

    private fun sendStroke(start: Int, stop: Int) {
        val s = start.coerceIn(0, 90)
        val e = stop.coerceIn(s + 10, 100)
        if (lastStroke == s to e) return
        lastStroke = s to e
        out.setStroke(s, e)
    }
}
