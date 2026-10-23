package com.hismith.session.core

import org.json.JSONObject

/**
 * Commands a remote controller (browser behind the one-time link) may send. The relay server
 * only forwards them; the phone validates every field here and the [CommandArbiter] decides.
 */
sealed class RemoteCommand {
    object Stop : RemoteCommand()
    object ManualStart : RemoteCommand()
    object ManualEnd : RemoteCommand()
    data class Speed(val percent: Int) : RemoteCommand()
    data class AutoStart(
        val minMinutes: Int,
        val maxMinutes: Int,
        val intensity: Int,
        val style: SessionStyle? = null,
    ) : RemoteCommand()
    object AutoEnd : RemoteCommand()
    object FinalNow : RemoteCommand()
    data class Intensity(val percent: Int) : RemoteCommand()
    object Pause : RemoteCommand()
    object Resume : RemoteCommand()
    /** Spray [ml] with the Lube Launcher at [pumpPct] % of the owner's safe max pump speed. */
    data class Spray(val ml: Int, val pumpPct: Int) : RemoteCommand()
    data class Action(val action: QuickAction) : RemoteCommand()
    data class Style(val style: SessionStyle) : RemoteCommand()

    companion object {
        /** Parses `{"k": "...", ...}`. Unknown or out-of-range input gives null (ignored). */
        fun parse(c: JSONObject?): RemoteCommand? {
            if (c == null) return null
            return when (c.optString("k")) {
                "stop" -> Stop
                "manual_start" -> ManualStart
                "manual_end" -> ManualEnd
                "speed" -> c.intIn("v", 0, 100)?.let { Speed(it) }
                "auto_start" -> {
                    val min = c.intIn("min", 1, 240) ?: return null
                    val max = c.intIn("max", 0, 240) ?: return null
                    val intensity = c.intIn("intensity", 0, 100) ?: 50
                    val style = if (c.has("style")) SessionStyle.parse(c.optString("style")) ?: return null else null
                    AutoStart(min, if (max == 0) 0 else maxOf(min, max), intensity, style)
                }
                "auto_end" -> AutoEnd
                "final_now" -> FinalNow
                "intensity" -> c.intIn("v", 0, 100)?.let { Intensity(it) }
                "pause" -> Pause
                "resume" -> Resume
                "action" -> QuickAction.parse(c.optString("a"))?.let { Action(it) }
                "style" -> SessionStyle.parse(c.optString("v"))?.let { Style(it) }
                "spray" -> {
                    val ml = c.intIn("ml", 1, Limits.MANUAL_MAX_ML.toInt()) ?: return null
                    val pump = c.intIn("pump", 10, 100) ?: return null
                    Spray(ml, pump)
                }
                else -> null
            }
        }

        private fun JSONObject.intIn(key: String, lo: Int, hi: Int): Int? {
            if (!has(key)) return null
            val v = opt(key)
            val n = when (v) {
                is Int -> v
                is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else return null
                is Double -> if (v.isFinite() && v == Math.floor(v)) v.toInt() else return null
                else -> return null
            }
            return if (n in lo..hi) n else null
        }
    }
}

/**
 * Fail-safe for the remote link. The phone hears the controller via heartbeats relayed by the
 * server. If it goes quiet (closed browser, lost network, dead server – the phone cannot tell
 * them apart) motion is stopped after [stopAfterMs] and the controller is revoked after
 * [revokeAfterMs]. Coming back in time never restarts anything by itself.
 */
class RemoteWatchdog(var stopAfterMs: Long = 4_000, var revokeAfterMs: Long = 8_000) {
    enum class Action { STOP, REVOKE }

    private var lastHeard = -1L
    private var silentSince = -1L
    private var stopped = false
    private var revoked = false

    val isSilent: Boolean get() = silentSince >= 0

    /** A controller was admitted. */
    fun start(now: Long) {
        lastHeard = now
        silentSince = -1
        stopped = false
        revoked = false
    }

    fun heard(now: Long) {
        if (lastHeard < 0 || revoked) return
        lastHeard = now
        silentSince = -1
        stopped = false
    }

    /** Explicit loss signal (controller socket closed, host link down). */
    fun lost(now: Long) {
        if (lastHeard < 0 || revoked) return
        if (silentSince < 0) silentSince = now
    }

    fun reset() {
        lastHeard = -1
        silentSince = -1
        stopped = false
        revoked = false
    }

    /**
     * Returns the action to take now, each at most once per silence. Silence is counted from
     * the last heartbeat (heartbeats come every second), so an explicit loss signal and a
     * half-open connection that just goes quiet are treated the same way.
     */
    fun check(now: Long): Action? {
        if (lastHeard < 0 || revoked) return null
        val quiet = now - lastHeard
        if (!stopped && quiet >= stopAfterMs) {
            stopped = true
            return Action.STOP
        }
        if (stopped && quiet >= revokeAfterMs) {
            revoked = true
            return Action.REVOKE
        }
        return null
    }

    val wasStopped: Boolean get() = stopped
    val wasRevoked: Boolean get() = revoked
}
