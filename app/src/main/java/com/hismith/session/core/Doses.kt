package com.hismith.session.core

/**
 * Dose kinds in priority order (spec): STOP → comm loss → final/SUPER → EXTRA → normal → micro.
 * STOP and comm loss are not doses; they go through [DoseController.stopAll].
 */
enum class DoseKind(val priority: Int, val label: String) {
    MICRO(1, "Mikrodos"),
    MANUAL(2, "Dos"),
    EXTRA(3, "EXTRA SPRUT"),
    SUPER(4, "SUPER SPRUT"),
    FINAL(4, "Final"),
    /** Prime / empty the tube / calibration run. Never pre-empted by doses. */
    SERVICE(5, "Underhåll");
}

data class DoseRequest(
    val kind: DoseKind,
    val ml: Double,
    val source: Source,
    /** Only for [DoseKind.SERVICE]: run at this speed (negative = reverse) for [serviceMs]. */
    val serviceSpeed: Int = 0,
    val serviceMs: Long = 0,
)

/** User settings for the launcher. Validated through [sanitized]. */
data class LubeSettings(
    val extraMl: Double = 15.0,
    val superMl: Double = 50.0,
    val manualMl: Double = 5.0,
    val finalMinMl: Double = 15.0,
    val finalMaxMl: Double = 50.0,
    val microOral: MicroDoseSettings = MicroDoseSettings(0.5, 25, 50),
    val microAnal: MicroDoseSettings = MicroDoseSettings(1.5, 20, 45),
    /** Wanted flow (ml/s) for micro doses. */
    val flowMicro: Double = 1.0,
    /** Wanted flow for manual doses and EXTRA. */
    val flowNormal: Double = 3.0,
    /** Wanted flow for SUPER and the final. */
    val flowStrong: Double = 5.0,
    /** Allowed max pump speed (%) until a physical test says otherwise. */
    val maxPumpSpeed: Int = 80,
    /** Max total volume per session (all doses). */
    val maxSessionMl: Double = 300.0,
) {
    fun sanitized(): LubeSettings {
        val fMin = finalMinMl.coerceIn(1.0, Limits.FINAL_MAX_ML)
        return copy(
            extraMl = extraMl.coerceIn(1.0, Limits.EXTRA_MAX_ML),
            superMl = superMl.coerceIn(1.0, Limits.SUPER_MAX_ML),
            manualMl = manualMl.coerceIn(Limits.MANUAL_MIN_ML, Limits.MANUAL_MAX_ML),
            finalMinMl = fMin,
            finalMaxMl = finalMaxMl.coerceIn(fMin, Limits.FINAL_MAX_ML),
            microOral = microOral.sanitized(),
            microAnal = microAnal.sanitized(),
            flowMicro = flowMicro.coerceIn(0.05, 20.0),
            flowNormal = flowNormal.coerceIn(0.05, 20.0),
            flowStrong = flowStrong.coerceIn(0.05, 20.0),
            maxPumpSpeed = maxPumpSpeed.coerceIn(10, 100),
            maxSessionMl = maxSessionMl.coerceIn(10.0, Limits.SESSION_MAX_ML_CAP),
        )
    }

    fun microFor(mode: SessionMode): MicroDoseSettings = if (mode == SessionMode.ORAL) microOral else microAnal

    fun flowFor(kind: DoseKind): Double = when (kind) {
        DoseKind.MICRO -> flowMicro
        DoseKind.MANUAL, DoseKind.EXTRA -> flowNormal
        DoseKind.SUPER, DoseKind.FINAL -> flowStrong
        DoseKind.SERVICE -> flowNormal
    }

    fun clampMl(kind: DoseKind, ml: Double): Double = when (kind) {
        DoseKind.MICRO -> ml.coerceIn(Limits.MICRO_MIN_ML, Limits.MICRO_MAX_ML)
        DoseKind.MANUAL -> ml.coerceIn(Limits.MANUAL_MIN_ML, Limits.MANUAL_MAX_ML)
        DoseKind.EXTRA -> ml.coerceIn(1.0, Limits.EXTRA_MAX_ML)
        DoseKind.SUPER -> ml.coerceIn(1.0, Limits.SUPER_MAX_ML)
        DoseKind.FINAL -> ml.coerceIn(1.0, Limits.FINAL_MAX_ML)
        DoseKind.SERVICE -> ml
    }
}

/**
 * Whatever physically pumps: MegaLube over Bluetooth SPP, the simulator, or the original
 * Hismith BLE launcher. The controller streams [run] with a short lease, so a lost app means
 * the pump stops by itself.
 */
interface LubeDriver {
    val displayName: String
    val isReady: Boolean
    /** False for launchers that only know "squirt" (speed is ignored). */
    val supportsSpeed: Boolean
    val supportsReverse: Boolean
    fun run(speedPct: Int, leaseMs: Long)
    fun stop()
}

sealed class DoseResult {
    data class Started(val plan: DosePlan) : DoseResult()
    data class Queued(val position: Int) : DoseResult()
    data class Preempted(val replaced: DoseKind, val plan: DosePlan) : DoseResult()
    data class Rejected(val reason: String) : DoseResult()
}

/** Snapshot of the running dose for the UI / remote status. */
data class ActiveDoseInfo(
    val kind: DoseKind,
    val ml: Double,
    val deliveredMl: Double,
    val speedPct: Int,
    val remainingMs: Long,
    val segment: Int,
    val segments: Int,
)

/**
 * Dose queue + executor. Rules:
 *  - a higher-priority dose pre-empts the running one;
 *  - a micro dose never interrupts or stacks on anything – it is dropped when busy;
 *  - other doses queue (max [MAX_QUEUE]) behind an equal or higher one;
 *  - the session total never exceeds [LubeSettings.maxSessionMl].
 *
 * Execution streams `RUN:<speed>:<lease>` every [SEND_INTERVAL_MS]; the last call carries the
 * exact remaining time, so the firmware ends the dose itself even if the app stalls.
 */
class DoseController(
    private val settings: () -> LubeSettings,
    private val flowModel: () -> FlowModel,
    private val onEvent: (String) -> Unit = {},
    private val onDelivered: (kind: DoseKind, ml: Double) -> Unit = { _, _ -> },
) {
    companion object {
        const val SEND_INTERVAL_MS = 250L
        const val LEASE_MS = 700L
        const val MAX_QUEUE = 3
    }

    var driver: LubeDriver? = null

    private class Active(
        val request: DoseRequest,
        val plan: DosePlan,
        var segment: Int,
        var segmentEnd: Long,
        var pauseUntil: Long,
        var lastSend: Long,
        var lastTick: Long,
        var delivered: Double,
    )

    private var active: Active? = null
    private val queue = ArrayList<DoseRequest>()

    /** Volume delivered (estimated) since [resetSession]. */
    var sessionDeliveredMl = 0.0
        private set

    val isBusy: Boolean get() = active != null
    val queuedKinds: List<DoseKind> get() = queue.map { it.kind }

    fun resetSession() {
        sessionDeliveredMl = 0.0
    }

    fun activeInfo(now: Long): ActiveDoseInfo? {
        val a = active ?: return null
        val remainingSeg = if (a.pauseUntil > now) 0L else (a.segmentEnd - now).coerceAtLeast(0)
        val later = a.plan.segmentsMs.drop(a.segment + 1).sum() +
            (a.plan.segmentsMs.size - 1 - a.segment) * Limits.SEGMENT_PAUSE_MS
        val pause = (a.pauseUntil - now).coerceAtLeast(0)
        return ActiveDoseInfo(
            kind = a.request.kind,
            ml = a.plan.ml,
            deliveredMl = a.delivered,
            speedPct = a.plan.speedPct,
            remainingMs = remainingSeg + pause + later,
            segment = a.segment + 1,
            segments = a.plan.segmentsMs.size,
        )
    }

    /** Estimated run time for [ml] of [kind] with the current calibration (0 if impossible). */
    fun estimateMs(kind: DoseKind, ml: Double): Long {
        val s = settings().sanitized()
        return flowModel().plan(s.clampMl(kind, ml), wantedFlow(s, kind), maxSpeedFor(s))?.totalMs ?: 0L
    }

    private fun maxSpeedFor(s: LubeSettings): Int =
        if (driver?.supportsSpeed == false) 100 else s.maxPumpSpeed

    /** A launcher without speed control always runs flat out, so plan at its full flow. */
    private fun wantedFlow(s: LubeSettings, kind: DoseKind): Double =
        if (driver?.supportsSpeed == false) Double.MAX_VALUE else s.flowFor(kind)

    private fun planFor(req: DoseRequest): DosePlan? {
        if (req.kind == DoseKind.SERVICE) {
            val speed = req.serviceSpeed.coerceIn(-Limits.MAX_REVERSE_PCT, 100)
            if (speed == 0 || req.serviceMs <= 0) return null
            val segs = ArrayList<Long>()
            var left = req.serviceMs.coerceAtMost(10 * 60_000L)
            while (left > 0) {
                val seg = left.coerceAtMost(Limits.MAX_SEGMENT_MS)
                segs += seg
                left -= seg
            }
            val flow = flowModel().flowAt(kotlin.math.abs(speed))
            return DosePlan(speed, flow, flow * req.serviceMs / 1000.0, segs)
        }
        val s = settings().sanitized()
        return flowModel().plan(s.clampMl(req.kind, req.ml), wantedFlow(s, req.kind), maxSpeedFor(s))
    }

    fun submit(req: DoseRequest, now: Long): DoseResult {
        val d = driver
        if (d == null || !d.isReady) return DoseResult.Rejected("Ingen launcher ansluten")
        if (req.kind == DoseKind.SERVICE && (active != null || queue.isNotEmpty())) {
            return DoseResult.Rejected("Vänta tills pågående dos är klar")
        }
        if (req.kind == DoseKind.SERVICE && req.serviceSpeed < 0 && !d.supportsReverse) {
            return DoseResult.Rejected("Launchern kan inte köra bakåt")
        }
        val s = settings().sanitized()
        var request = if (req.kind == DoseKind.SERVICE) req else req.copy(ml = s.clampMl(req.kind, req.ml))

        if (request.kind != DoseKind.SERVICE) {
            val committed = sessionDeliveredMl +
                (active?.let { it.plan.ml - it.delivered } ?: 0.0) +
                queue.sumOf { it.ml }
            val left = s.maxSessionMl - committed
            if (left < request.ml) {
                if (request.kind == DoseKind.MICRO || left < 1.0) {
                    return DoseResult.Rejected("Maxvolym per session (${s.maxSessionMl.toInt()} ml) nådd")
                }
                request = request.copy(ml = left)
            }
        }

        val current = active
        if (current == null) {
            val plan = planFor(request) ?: return DoseResult.Rejected("Kan inte planera dosen (kalibrera pumpen)")
            start(request, plan, now)
            return DoseResult.Started(plan)
        }
        if (current.request.kind == DoseKind.SERVICE) return DoseResult.Rejected("Underhåll pågår")
        if (request.kind == DoseKind.MICRO) return DoseResult.Rejected("Upptagen – mikrodos hoppas över")
        if (request.kind.priority > current.request.kind.priority) {
            val plan = planFor(request) ?: return DoseResult.Rejected("Kan inte planera dosen (kalibrera pumpen)")
            finishAccounting(current)
            onEvent("${current.request.kind.label} ersattes av ${request.kind.label}")
            start(request, plan, now)
            return DoseResult.Preempted(current.request.kind, plan)
        }
        if (queue.size >= MAX_QUEUE) return DoseResult.Rejected("Kön är full")
        queue += request
        queue.sortByDescending { it.kind.priority } // stable: FIFO within the same priority
        return DoseResult.Queued(queue.indexOf(request) + 1)
    }

    private fun start(req: DoseRequest, plan: DosePlan, now: Long) {
        active = Active(
            request = req,
            plan = plan,
            segment = 0,
            segmentEnd = now + plan.segmentsMs[0],
            pauseUntil = 0L,
            lastSend = Long.MIN_VALUE / 2,
            lastTick = now,
            delivered = 0.0,
        )
        tick(now)
    }

    private fun finishAccounting(a: Active) {
        if (a.request.kind != DoseKind.SERVICE) sessionDeliveredMl += a.delivered
        if (a.delivered > 0) onDelivered(a.request.kind, a.delivered)
    }

    /** Stop everything (STOP, comm loss, Pocket, link lost). Idempotent. */
    fun stopAll(reason: String? = null) {
        val a = active
        queue.clear()
        if (a != null) {
            active = null
            finishAccounting(a)
            reason?.let { onEvent("${a.request.kind.label} avbruten: $it") }
        }
        driver?.stop()
    }

    /** Called when the launcher link dropped: the firmware has already stopped the pump. */
    fun onDriverLost() {
        val a = active ?: run { queue.clear(); return }
        active = null
        queue.clear()
        finishAccounting(a)
        onEvent("${a.request.kind.label} avbruten: launchern tappade kontakten")
    }

    fun tick(now: Long) {
        val a = active ?: return
        val d = driver
        if (d == null || !d.isReady) {
            onDriverLost()
            return
        }
        val dt = (now - a.lastTick).coerceIn(0, 1000)
        a.lastTick = now

        if (a.pauseUntil > 0) {
            if (now < a.pauseUntil) return
            a.pauseUntil = 0
            a.segmentEnd = now + a.plan.segmentsMs[a.segment]
            a.lastSend = Long.MIN_VALUE / 2
        } else if (dt > 0) {
            a.delivered = (a.delivered + kotlin.math.abs(a.plan.mlPerSec) * dt / 1000.0)
                .coerceAtMost(if (a.request.kind == DoseKind.SERVICE) Double.MAX_VALUE else a.plan.ml)
        }

        val remaining = a.segmentEnd - now
        if (remaining <= 0) {
            if (a.segment + 1 < a.plan.segmentsMs.size) {
                a.segment++
                a.pauseUntil = now + Limits.SEGMENT_PAUSE_MS
                return
            }
            active = null
            finishAccounting(a)
            onEvent("${a.request.kind.label} klar (${"%.1f".format(a.delivered)} ml)")
            startNextQueued(now)
            return
        }
        if (now - a.lastSend >= SEND_INTERVAL_MS) {
            d.run(a.plan.speedPct, remaining.coerceAtMost(LEASE_MS))
            a.lastSend = now
        }
    }

    private fun startNextQueued(now: Long) {
        while (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            val plan = planFor(next) ?: continue
            start(next, plan, now)
            return
        }
    }
}
