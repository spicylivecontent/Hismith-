package com.hismith.session.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** One calibration measurement: running at [speedPct] gave [mlPerSec]. */
data class CalPoint(val speedPct: Int, val mlPerSec: Double)

/**
 * Pump flow as a function of speed, built from calibration points (piecewise linear through
 * the origin). Turns "volume + wanted flow" into "speed + run time":
 *
 *     wanted ml + wanted ml/s → calibrated speed → run time → actual dose
 */
class FlowModel(points: List<CalPoint>, val calibrated: Boolean) {

    val points: List<CalPoint> = points
        .filter { it.speedPct in 1..100 && it.mlPerSec > 0.0 }
        .groupBy { it.speedPct }
        .map { (speed, list) -> CalPoint(speed, list.map { it.mlPerSec }.average()) }
        .sortedBy { it.speedPct }

    fun flowAt(speedPct: Int): Double {
        val s = speedPct.coerceIn(0, 100)
        if (s == 0 || points.isEmpty()) return 0.0
        var prevS = 0
        var prevF = 0.0
        for (p in points) {
            if (s <= p.speedPct) {
                val span = (p.speedPct - prevS).coerceAtLeast(1)
                return prevF + (p.mlPerSec - prevF) * (s - prevS) / span
            }
            prevS = p.speedPct
            prevF = p.mlPerSec
        }
        // Above the last point: continue the last slope (never below the last value).
        val slope = if (points.size >= 2) {
            val a = points[points.size - 2]
            val b = points.last()
            (b.mlPerSec - a.mlPerSec) / (b.speedPct - a.speedPct).coerceAtLeast(1)
        } else {
            points.last().mlPerSec / points.last().speedPct
        }
        return max(points.last().mlPerSec, points.last().mlPerSec + slope * (s - points.last().speedPct))
    }

    /** Lowest speed in [minSpeed]..[maxSpeed] that reaches [mlPerSec]; [maxSpeed] if none does. */
    fun speedForFlow(mlPerSec: Double, minSpeed: Int, maxSpeed: Int): Int {
        val lo = minSpeed.coerceIn(1, 100)
        val hi = maxSpeed.coerceIn(lo, 100)
        for (s in lo..hi) if (flowAt(s) >= mlPerSec) return s
        return hi
    }

    fun maxFlow(maxSpeed: Int): Double = flowAt(maxSpeed.coerceIn(1, 100))

    /**
     * Plan a dose of [ml] at about [wantedMlPerSec], never faster than [maxSpeed] %.
     * Long doses are split in [Limits.MAX_SEGMENT_MS] chunks with a short pause so the
     * MegaLube firmware never reaches its 120 s continuous-run lock.
     */
    fun plan(ml: Double, wantedMlPerSec: Double, maxSpeed: Int, minSpeed: Int = 5): DosePlan? {
        if (ml <= 0.0) return null
        var speed = speedForFlow(wantedMlPerSec, minSpeed, maxSpeed)
        var flow = flowAt(speed)
        if (flow <= 0.0) return null
        // Prefer one segment: speed up (up to maxSpeed) rather than chopping a dose into pieces.
        while (ml / flow * 1000.0 > Limits.MAX_SEGMENT_MS && speed < maxSpeed.coerceAtMost(100)) {
            speed++
            flow = flowAt(speed)
        }
        val pureMs = ml / flow * 1000.0
        val segmentCount = max(1, ceil(pureMs / Limits.MAX_SEGMENT_MS).toInt())
        val perSegment = pureMs / segmentCount
        val segments = List(segmentCount) {
            (perSegment + rampCompensationMs(speed)).roundToLong().coerceAtLeast(Limits.MIN_RUN_MS)
        }
        return DosePlan(speed, flow, ml, segments)
    }

    fun toStorageString(): String = points.joinToString(";") { "${it.speedPct}:${it.mlPerSec}" }

    companion object {
        /** Un-calibrated guess: 500 ml/min pump with gel ≈ 5 ml/s at 100 %. */
        val DEFAULT = FlowModel(listOf(CalPoint(100, 5.0)), calibrated = false)

        fun fromStorageString(s: String?): FlowModel {
            if (s.isNullOrBlank()) return DEFAULT
            val pts = s.split(';').mapNotNull { part ->
                val kv = part.split(':')
                val sp = kv.getOrNull(0)?.toIntOrNull()
                val f = kv.getOrNull(1)?.toDoubleOrNull()
                if (sp != null && f != null) CalPoint(sp, f) else null
            }
            val model = FlowModel(pts, calibrated = true)
            return if (model.points.isEmpty()) DEFAULT else model
        }

        /**
         * MegaLube ramps 0→100 % in 0.4 s and 100→0 % in 0.25 s. Half of the ramp-up is lost
         * volume, half of the ramp-down is extra volume, so run a little longer.
         */
        fun rampCompensationMs(speedPct: Int): Double = speedPct.coerceIn(0, 100) / 100.0 * (400.0 - 250.0) / 2.0
    }
}

/** How a dose is executed: constant [speedPct] for each segment, with pauses in between. */
data class DosePlan(
    val speedPct: Int,
    val mlPerSec: Double,
    val ml: Double,
    val segmentsMs: List<Long>,
) {
    val runMs: Long get() = segmentsMs.sum()
    val totalMs: Long get() = runMs + (segmentsMs.size - 1) * Limits.SEGMENT_PAUSE_MS
}
