package com.hismith.session.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** One output sample from the engine. Speeds are 0..100 before the arbiter applies limits. */
data class EngineFrame(
    val speed: Int,
    val strokeStart: Int,
    val strokeStop: Int,
    val label: String,
    val behavior: AutoEngine.Behavior,
    /** Lube the engine wants now (micro dose or the final), or null. */
    val lube: LubeCue?,
    val finished: Boolean,
)

data class LubeCue(val kind: DoseKind, val ml: Double)

/**
 * Unpredictable session engine with variation on three time scales:
 *
 *  - **Macro**: picks the next behaviour (rhythm, waves, burst, hold, tease, edge) with weights
 *    that punish recent use, reward behaviours not seen for a while and punish sequences that
 *    were common in earlier sessions ([MotionHistory]).
 *  - **Meso**: every behaviour is generated fresh – targets, durations, ramps, easing, overshoot,
 *    stutters and dips are all drawn per occurrence. A burst is not always 100 %, a hold not
 *    always dead still.
 *  - **Micro**: a slowly correlated (Ornstein–Uhlenbeck) wander on top of the base speed, so it
 *    drifts organically instead of jumping between random percentages.
 *
 * The engine is pure: time comes in through [tick]. Same seed + same config + same clock gives
 * the same output, which is what the unit tests rely on.
 */
class AutoEngine(
    val config: SessionConfig,
    val seed: Long,
    private val history: MotionHistory = MotionHistory(),
    /** Run time (ms) the launcher needs for a final dose of the given ml; 0 = no lube. */
    private val finalDoseMs: (Double) -> Long = { 0L },
) {
    enum class Behavior(val label: String) {
        WARMUP("Uppvärmning"),
        RHYTHM("Rytm"),
        WAVE("Vågor"),
        BURST("Max burst"),
        HOLD("Deep hold"),
        TEASE("Tease"),
        EDGE("Kanten"),
        FINAL("Final");

        companion object {
            val PICKABLE = listOf(RHYTHM, WAVE, BURST, HOLD, TEASE, EDGE)
        }
    }

    enum class Easing { LINEAR, SMOOTH, SINE, EASE_IN, EASE_OUT }

    private data class Segment(
        val target: Double,
        val rampMs: Long,
        val holdMs: Long,
        val easing: Easing,
        val sigma: Double,
        val strokeStart: Int,
        val strokeStop: Int,
        val label: String? = null,
        val finalDose: Boolean = false,
    )

    private val rng = Random(seed)
    private var intensity = config.intensity.coerceIn(0, 100) / 100.0
    private var style = config.style

    // Engine clock: ms since start, paused time excluded.
    private var startWall = 0L
    private var pausedAt = -1L
    private var pausedTotal = 0L

    var durationMs = 0L
        private set
    var started = false
        private set
    var finished = false
        private set
    val isPaused: Boolean get() = pausedAt >= 0

    private var behavior = Behavior.WARMUP
    private var segmentLabel: String? = null
    private val segments = ArrayDeque<Segment>()
    private var rampFrom = 0.0
    private var rampTo = 0.0
    private var rampStart = 0L
    private var rampMs = 1L
    private var easing = Easing.SMOOTH
    private var segmentEnd = 0L
    private var sigma = 0.0
    private var lastBase = 0.0

    private var noise = 0.0
    private var lastNoiseT = 0L

    private var strokeStart = 12.0
    private var strokeStop = 80.0
    private var strokeTargetStart = 12
    private var strokeTargetStop = 80
    private var lastStrokeT = 0L

    /** Behaviours of this session, in order (used for anti-repeat and the history). */
    val sequence = ArrayList<Behavior>()
    private val lastUsed = HashMap<Behavior, Int>()

    private val microEnabled = config.autoLube && config.mode.lubeAllowed
    private val micro = config.microDose.sanitized()
    private var nextMicroAt = Long.MAX_VALUE

    /** Cues waiting to be emitted (one per tick): micro at burst start, surprise squirts. */
    private val pendingCues = ArrayDeque<LubeCue>()
    private var lastMicroAt = Long.MIN_VALUE / 2

    private val surpriseEnabled = config.surprise != SurpriseLevel.OFF
    private var nextSurpriseAt = Long.MAX_VALUE
    private var surpriseLabelUntil = 0L

    private val finalEnabled = config.finalEnabled
    private var finalVolumeMl = 0.0
    private var finalPlan: List<Segment> = emptyList()
    private var finalLeadMs = 0L
    private var finalStarted = false
    private var finalDosePending = false

    val finalStartedFlag: Boolean get() = finalStarted
    val plannedFinalMl: Double get() = finalVolumeMl

    fun start(nowWall: Long) {
        startWall = nowWall
        pausedAt = -1
        pausedTotal = 0
        started = true
        finished = false
        durationMs = if (config.maxMinutes <= 0) 0L else {
            val lo = config.minMinutes.coerceAtLeast(1) * 60_000L
            val hi = maxOf(config.minMinutes, config.maxMinutes).coerceAtLeast(1) * 60_000L
            if (hi <= lo) lo else lo + (rng.nextLong(hi - lo + 1) / 1000L) * 1000L
        }
        if (microEnabled) nextMicroAt = uniformMs(micro.intervalMinSec * 1000L, micro.intervalMaxSec * 1000L)
        if (surpriseEnabled) nextSurpriseAt = nextSurpriseDelay()
        planFinal()
        beginBehavior(Behavior.WARMUP, 0L)
    }

    fun setIntensity(value: Int) {
        intensity = value.coerceIn(0, 100) / 100.0
    }

    val intensityPercent: Int get() = (intensity * 100).roundToInt()

    /** Change style live; it applies from the next behaviour. */
    fun setStyle(value: SessionStyle) {
        style = value
    }

    val currentStyle: SessionStyle get() = style

    /**
     * One-tap action: switch to the matching behaviour right now (ramped as usual).
     * Ignored before start, while paused and during the final.
     */
    fun trigger(action: QuickAction, nowWall: Long): Boolean {
        if (!started || finished || pausedAt >= 0 || finalStarted) return false
        val t = clock(nowWall)
        val b = when (action) {
            QuickAction.BOOST -> Behavior.BURST
            QuickAction.WAVE -> Behavior.WAVE
            QuickAction.EDGE -> Behavior.EDGE
            QuickAction.REST -> Behavior.HOLD
        }
        beginBehavior(b, t)
        return true
    }

    private fun nextSurpriseDelay(): Long =
        uniformMs(config.surprise.minSec * 1000L, config.surprise.maxSec * 1000L)

    fun elapsedMs(nowWall: Long): Long = if (!started) 0L else clock(nowWall)

    fun remainingMs(nowWall: Long): Long? =
        if (durationMs <= 0) null else (durationMs - elapsedMs(nowWall)).coerceAtLeast(0)

    fun progress(nowWall: Long): Float =
        if (durationMs <= 0) 0f else (elapsedMs(nowWall).toFloat() / durationMs).coerceIn(0f, 1f)

    private fun clock(nowWall: Long): Long {
        val ref = if (pausedAt >= 0) pausedAt else nowWall
        return (ref - startWall - pausedTotal).coerceAtLeast(0)
    }

    fun pause(nowWall: Long) {
        if (pausedAt < 0 && started) pausedAt = nowWall
    }

    /** Resume after a pause: the speed restarts from zero and ramps back – never a jump. */
    fun resume(nowWall: Long) {
        if (pausedAt < 0) return
        pausedTotal += nowWall - pausedAt
        pausedAt = -1
        val t = clock(nowWall)
        rampFrom = 0.0
        lastBase = 0.0
        rampStart = t
        rampMs = 2500
        if (segmentEnd < t + rampMs) segmentEnd = t + rampMs
        noise = 0.0
        lastNoiseT = t
    }

    /** "Final nu": jump straight to the planned final (or end soon if no end time was set). */
    fun finishSoon(nowWall: Long) {
        if (finalStarted || finished) return
        val t = clock(nowWall)
        if (durationMs <= 0) planFinal(forcedDuration = true)
        durationMs = t + finalLeadMs
    }

    fun tick(nowWall: Long): EngineFrame {
        if (!started || finished) return frame(0, null, finished)
        if (pausedAt >= 0) return frame(0, null, false, labelOverride = "Pausad")
        val t = clock(nowWall)

        if (durationMs > 0 && t >= durationMs) {
            finished = true
            history.recordSession(sequence)
            return frame(0, null, true, labelOverride = "Session klar")
        }
        if (durationMs > 0 && !finalStarted && t >= durationMs - finalLeadMs) startFinal(t)

        if (surpriseEnabled && !finalStarted && t >= nextSurpriseAt) {
            // Surprise: a sudden burst, together with an EXTRA squirt when lube is allowed.
            nextSurpriseAt = t + nextSurpriseDelay()
            surpriseLabelUntil = t + 4_000
            if (config.mode.lubeAllowed && config.surpriseMl > 0) pendingCues.addFirst(LubeCue(DoseKind.EXTRA, config.surpriseMl))
            beginBehavior(Behavior.BURST, t, microAllowed = false)
        }

        var guard = 0
        while (t >= segmentEnd && guard++ < 64) {
            if (segments.isEmpty()) {
                if (finalStarted) {
                    // Final ran out before the clock did: rest at zero until the end.
                    segments.addLast(Segment(0.0, 800, 60_000, Easing.SMOOTH, 0.0, 30, 95, "Nedvarvning"))
                } else {
                    beginBehavior(pickNext(), segmentEnd)
                    continue
                }
            }
            startSegment(segments.removeFirst(), segmentEnd, t)
        }

        val p = ((t - rampStart).toDouble() / rampMs).coerceIn(0.0, 1.0)
        val base = rampFrom + (rampTo - rampFrom) * ease(easing, p)
        lastBase = base

        val dt = ((t - lastNoiseT).coerceIn(0, 1000)) / 1000.0
        lastNoiseT = t
        if (dt > 0) {
            val tau = 4.0
            noise += -noise * dt / tau + sigma * sqrt(2.0 * dt / tau) * gaussian()
            noise = noise.coerceIn(-3 * sigma - 0.01, 3 * sigma + 0.01)
        }
        val floor = if (config.nonstop && behavior != Behavior.FINAL) 12.0 else 0.0
        val speed = when {
            rampTo <= 0.5 && p >= 1.0 && floor == 0.0 -> 0
            else -> (base + noise).coerceIn(floor, 100.0).roundToInt()
        }

        if (t - lastStrokeT >= 400) {
            lastStrokeT = t
            strokeStart += ((strokeTargetStart - strokeStart) * 0.25).coerceIn(-8.0, 8.0)
            strokeStop += ((strokeTargetStop - strokeStop) * 0.25).coerceIn(-8.0, 8.0)
            if (strokeStart > strokeStop - 15) strokeStart = (strokeStop - 15).coerceAtLeast(0.0)
        }

        var cue: LubeCue? = null
        if (finalDosePending && finalVolumeMl > 0) {
            finalDosePending = false
            pendingCues.clear()
            cue = LubeCue(DoseKind.FINAL, finalVolumeMl)
        } else if (pendingCues.isNotEmpty() && !finalStarted) {
            cue = pendingCues.removeFirst()
        } else if (microEnabled && !finalStarted && t >= nextMicroAt) {
            nextMicroAt = t + uniformMs(micro.intervalMinSec * 1000L, micro.intervalMaxSec * 1000L)
            lastMicroAt = t
            cue = LubeCue(DoseKind.MICRO, micro.ml)
        }
        val label = if (t < surpriseLabelUntil) "Överraskning!" else null
        return frame(speed, cue, false, labelOverride = label)
    }

    private fun frame(speed: Int, cue: LubeCue?, done: Boolean, labelOverride: String? = null) = EngineFrame(
        speed = speed,
        strokeStart = strokeStart.roundToInt().coerceIn(0, 100),
        strokeStop = strokeStop.roundToInt().coerceIn(0, 100),
        label = labelOverride ?: segmentLabel ?: behavior.label,
        behavior = behavior,
        lube = cue,
        finished = done,
    )

    // ------------------------------------------------------------------ macro level

    private fun baseWeight(b: Behavior): Double = when (b) {
        Behavior.RHYTHM -> 0.30 * style.rhythm
        Behavior.WAVE -> 0.16 * style.wave
        Behavior.BURST -> 0.15 * (0.6 + 0.8 * intensity) * style.burst
        Behavior.HOLD -> if (config.nonstop) 0.0 else 0.12 * (1.25 - 0.6 * intensity) * style.hold
        Behavior.TEASE -> 0.13 * style.tease
        Behavior.EDGE -> 0.11 * (0.5 + intensity) * style.edge
        else -> 0.0
    }

    /** Weights for the next behaviour (exposed for tests). */
    internal fun weights(): Map<Behavior, Double> {
        val n = sequence.size
        val prev1 = sequence.lastOrNull()
        val prev2 = if (n >= 2) sequence[n - 2] else null
        return Behavior.PICKABLE.associateWith { b ->
            var w = baseWeight(b)
            val last = lastUsed[b]
            if (last != null) {
                when (n - last) {
                    1 -> w *= 0.12
                    2 -> w *= 0.45
                    3 -> w *= 0.75
                    in 6..Int.MAX_VALUE -> w *= 1.3
                }
            } else if (n >= 4) {
                w *= 1.5 // novelty: not seen at all this session
            }
            if (prev1 != null && prev2 != null) {
                w /= 1.0 + 0.6 * history.count(prev2, prev1, b)
            }
            w
        }
    }

    private fun pickNext(): Behavior {
        val w = weights()
        val total = w.values.sum()
        if (total <= 0) return Behavior.RHYTHM
        var r = rng.nextDouble() * total
        for ((b, v) in w) {
            r -= v
            if (r <= 0) return b
        }
        return w.keys.last()
    }

    private fun beginBehavior(b: Behavior, at: Long, microAllowed: Boolean = true) {
        behavior = b
        if (b != Behavior.WARMUP && b != Behavior.FINAL) {
            lastUsed[b] = sequence.size
        }
        // Lube follows the motion: a micro dose as the burst starts, then the timer restarts.
        if (b == Behavior.BURST && microAllowed && microEnabled && config.microOnBurst &&
            at - lastMicroAt >= micro.intervalMinSec * 500L
        ) {
            pendingCues.addLast(LubeCue(DoseKind.MICRO, micro.ml))
            lastMicroAt = at
            nextMicroAt = at + uniformMs(micro.intervalMinSec * 1000L, micro.intervalMaxSec * 1000L)
        }
        sequence += b
        segments.clear()
        segments.addAll(
            when (b) {
                Behavior.WARMUP -> warmup()
                Behavior.RHYTHM -> rhythm()
                Behavior.WAVE -> wave()
                Behavior.BURST -> burst()
                Behavior.HOLD -> hold()
                Behavior.TEASE -> tease()
                Behavior.EDGE -> edge()
                Behavior.FINAL -> finalPlan
            }
        )
        segmentEnd = at
    }

    private fun startSegment(s: Segment, scheduledAt: Long, now: Long) {
        // Keep the timeline exact, but never schedule into the far past (e.g. after a long stall).
        val t0 = if (now - scheduledAt > 2_000) now else scheduledAt
        rampFrom = lastBase
        rampTo = s.target.coerceIn(0.0, 100.0)
        rampStart = t0
        val scale = if (behavior == Behavior.FINAL) 1.0 else style.rampScale
        rampMs = (s.rampMs * scale).toLong().coerceAtLeast(150)
        easing = s.easing
        sigma = if (rampTo <= 0.5) 0.0 else s.sigma * (if (behavior == Behavior.FINAL) 1.0 else style.sigmaScale)
        segmentEnd = t0 + rampMs + s.holdMs.coerceAtLeast(0)
        segmentLabel = s.label
        strokeTargetStart = s.strokeStart.coerceIn(0, 85)
        strokeTargetStop = s.strokeStop.coerceIn(strokeTargetStart + 15, 100)
        if (s.finalDose) finalDosePending = true
    }

    // ------------------------------------------------------------------ meso level

    private fun shift(): Double = (intensity - 0.5) * 24.0 + style.shift

    private fun lvl(lo: Double, hi: Double): Double {
        val v = uniform(lo, hi) + shift()
        val floor = if (config.nonstop) 15.0 else 5.0
        return v.coerceIn(floor, 100.0)
    }

    private fun randomEasing(vararg options: Easing) = options[rng.nextInt(options.size)]

    private fun warmup(): List<Segment> = listOf(
        Segment(uniform(18.0, 30.0), uniformMs(4_000, 7_000), uniformMs(4_000, 8_000), Easing.EASE_OUT, 1.5, 15, 70),
        Segment(lvl(28.0, 40.0), uniformMs(3_000, 5_000), uniformMs(6_000, 12_000), Easing.SMOOTH, 2.5, 12, 78),
    )

    private fun rhythm(): List<Segment> {
        val base = lvl(28.0, 52.0)
        return List(1 + rng.nextInt(3)) {
            Segment(
                (base + uniform(-7.0, 7.0)).coerceIn(8.0, 95.0),
                uniformMs(1_200, 3_000), uniformMs(6_000, 18_000),
                randomEasing(Easing.SMOOTH, Easing.SINE, Easing.EASE_OUT),
                3.5, rng.nextInt(8, 23), rng.nextInt(70, 93),
            )
        }
    }

    private fun wave(): List<Segment> {
        val trough = (uniform(15.0, 32.0) + shift() * 0.5).coerceIn(if (config.nonstop) 15.0 else 8.0, 60.0)
        val peak = lvl(55.0, 82.0).coerceAtLeast(trough + 15)
        val out = ArrayList<Segment>()
        repeat(2 + rng.nextInt(3)) {
            out += Segment((peak + uniform(-5.0, 5.0)).coerceAtMost(100.0), uniformMs(3_000, 6_000),
                uniformMs(500, 2_000), Easing.SINE, 2.0, rng.nextInt(8, 20), rng.nextInt(82, 97))
            out += Segment((trough + uniform(-4.0, 4.0)).coerceAtLeast(5.0), uniformMs(3_000, 6_000),
                uniformMs(500, 2_000), Easing.SINE, 2.0, rng.nextInt(20, 40), rng.nextInt(65, 85))
        }
        return out
    }

    private fun burst(): List<Segment> {
        val peak = (uniform(82.0, 100.0) + shift() * 0.5).coerceIn(70.0, 100.0)
        val out = ArrayList<Segment>()
        val strokeS = rng.nextInt(15, 31)
        val strokeE = rng.nextInt(55, 76)
        if (rng.nextDouble() < 0.35) {
            out += Segment((peak + uniform(4.0, 9.0)).coerceAtMost(100.0), uniformMs(600, 1_100),
                uniformMs(400, 900), Easing.EASE_OUT, 1.5, strokeS, strokeE)
            out += Segment(peak, 600, 0, Easing.SMOOTH, 2.5, strokeS, strokeE)
        } else {
            out += Segment(peak, uniformMs(700, 1_400), 0, randomEasing(Easing.EASE_OUT, Easing.SMOOTH), 2.5, strokeS, strokeE)
        }
        val hold = uniformMs(8_000, 22_000)
        if (rng.nextDouble() < 0.3) {
            val first = (hold * uniform(0.3, 0.6)).toLong()
            out[out.size - 1] = out.last().copy(holdMs = first)
            out += Segment(peak - uniform(15.0, 30.0), uniformMs(300, 600), uniformMs(800, 1_800), Easing.LINEAR, 1.0, strokeS, strokeE)
            out += Segment(peak, uniformMs(400, 800), hold - first, Easing.EASE_OUT, 2.5, strokeS, strokeE)
        } else {
            out[out.size - 1] = out.last().copy(holdMs = hold)
        }
        return out
    }

    private fun hold(): List<Segment> {
        val crawl = rng.nextDouble() < 0.35
        val target = if (crawl) uniform(5.0, 12.0) else 0.0
        return listOf(
            Segment(target, uniformMs(900, 2_000), uniformMs(6_000, 18_000), Easing.SMOOTH,
                if (crawl) 1.2 else 0.0, rng.nextInt(55, 76), rng.nextInt(85, 101))
        )
    }

    private fun tease(): List<Segment> {
        val out = ArrayList<Segment>()
        var lastType = -1
        val slowFloor = if (config.nonstop) 15.0 else 8.0
        repeat(3 + rng.nextInt(4)) {
            var type: Int
            do { type = rng.nextInt(3) } while (type == lastType)
            lastType = type
            out += when (type) {
                0 -> Segment(lvl(55.0, 78.0), uniformMs(500, 900), uniformMs(2_000, 4_000), Easing.EASE_OUT, 1.5,
                    rng.nextInt(5, 16), rng.nextInt(40, 61), "Tease – grunt")
                1 -> Segment(uniform(slowFloor, 22.0), uniformMs(1_200, 2_000), uniformMs(2_500, 5_000), Easing.SMOOTH, 1.0,
                    rng.nextInt(60, 81), rng.nextInt(85, 99), "Tease – djupt")
                else -> Segment(lvl(40.0, 60.0), uniformMs(800, 1_400), uniformMs(2_000, 4_000), Easing.SINE, 1.5,
                    rng.nextInt(10, 26), rng.nextInt(75, 96), "Tease – långa slag")
            }
        }
        return out
    }

    private fun edge(): List<Segment> {
        val out = ArrayList<Segment>()
        var level = maxOf(lastBase, 35.0)
        val top = (uniform(85.0, 100.0) + shift() * 0.3).coerceIn(75.0, 100.0)
        val steps = 3 + rng.nextInt(2)
        repeat(steps) { i ->
            level = if (i == steps - 1) top else (level + uniform(10.0, 16.0)).coerceAtMost(top)
            out += Segment(level, uniformMs(1_500, 3_000), uniformMs(2_000, 4_000), Easing.SMOOTH, 2.0,
                rng.nextInt(10, 25), rng.nextInt(75, 95), "Kanten – upp")
        }
        val drop = if (config.nonstop) uniform(15.0, 22.0) else uniform(0.0, 12.0)
        out += Segment(drop, uniformMs(400, 800), uniformMs(4_000, 8_000), Easing.EASE_OUT, 0.8,
            rng.nextInt(55, 75), rng.nextInt(85, 100), "Kanten – stopp")
        out += Segment(lvl(30.0, 45.0), 2_500, uniformMs(2_000, 4_000), Easing.SMOOTH, 2.5,
            rng.nextInt(10, 22), rng.nextInt(70, 90))
        return out
    }

    // ------------------------------------------------------------------ final

    /**
     * The final is planned up front so its start can be counted backwards from the end:
     * buildup + (pump time for the chosen volume) + cooldown + margin.
     */
    private fun planFinal(forcedDuration: Boolean = false) {
        if (durationMs <= 0 && !forcedDuration) {
            finalLeadMs = 0
            return
        }
        finalVolumeMl = if (finalEnabled && config.mode.lubeAllowed) {
            val lo = config.finalMinMl.coerceIn(1.0, Limits.FINAL_MAX_ML)
            val hi = config.finalMaxMl.coerceIn(lo, Limits.FINAL_MAX_ML)
            uniform(lo, hi)
        } else 0.0
        val doseMs = if (finalVolumeMl > 0) finalDoseMs(finalVolumeMl).coerceAtLeast(0) else 0L
        if (doseMs <= 0) finalVolumeMl = 0.0
        val climaxHold = if (doseMs > 0) doseMs + 1_500 else uniformMs(6_000, 10_000)
        val plan = listOf(
            Segment(uniform(65.0, 78.0), uniformMs(2_000, 3_000), uniformMs(3_000, 5_000), Easing.SMOOTH, 2.0, 12, 85, "Final – uppbyggnad"),
            Segment(uniform(88.0, 100.0), uniformMs(1_500, 2_500), uniformMs(3_000, 6_000), Easing.EASE_OUT, 2.0, 15, 75, "Final – klimax"),
            Segment(uniform(35.0, 55.0), 1_000, climaxHold, Easing.SMOOTH, 1.5, rng.nextInt(20, 36), rng.nextInt(88, 101),
                if (doseMs > 0) "Final – sprut" else "Final", finalDose = doseMs > 0),
            Segment(uniform(10.0, 16.0), uniformMs(2_500, 4_000), uniformMs(1_000, 2_000), Easing.EASE_IN, 1.0, 30, 95, "Nedvarvning"),
            Segment(0.0, 2_000, 0, Easing.SMOOTH, 0.0, 30, 95, "Nedvarvning"),
        )
        finalPlan = plan
        val lead = plan.sumOf { it.rampMs.coerceAtLeast(150) + it.holdMs } + 1_000
        finalLeadMs = if (forcedDuration || durationMs <= 0) lead else lead.coerceAtMost(durationMs / 2)
    }

    private fun startFinal(t: Long) {
        finalStarted = true
        beginBehavior(Behavior.FINAL, t)
        segmentEnd = t // start the first final segment right away
    }

    // ------------------------------------------------------------------ helpers

    private fun ease(e: Easing, p: Double): Double = when (e) {
        Easing.LINEAR -> p
        Easing.SMOOTH -> p * p * (3 - 2 * p)
        Easing.SINE -> (1 - cos(PI * p)) / 2
        Easing.EASE_IN -> p * p
        Easing.EASE_OUT -> 1 - (1 - p) * (1 - p)
    }

    private fun uniform(lo: Double, hi: Double): Double = if (hi <= lo) lo else lo + rng.nextDouble() * (hi - lo)

    private fun uniformMs(lo: Long, hi: Long): Long = if (hi <= lo) lo else lo + rng.nextLong(hi - lo + 1)

    private fun gaussian(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }
}

/**
 * Anti-repeat memory across sessions: decayed counts of behaviour trigrams. Sequences that were
 * common lately get a lower weight, so sessions keep drifting apart.
 */
class MotionHistory(private val counts: MutableMap<String, Double> = HashMap()) {

    fun count(a: AutoEngine.Behavior, b: AutoEngine.Behavior, c: AutoEngine.Behavior): Double =
        counts["${a.ordinal},${b.ordinal},${c.ordinal}"] ?: 0.0

    fun recordSession(seq: List<AutoEngine.Behavior>) {
        if (seq.size < 3) return
        val keys = counts.keys.toList()
        for (k in keys) {
            val v = counts.getValue(k) * DECAY
            if (v < 0.05) counts.remove(k) else counts[k] = v
        }
        for (i in 2 until seq.size) {
            val k = "${seq[i - 2].ordinal},${seq[i - 1].ordinal},${seq[i].ordinal}"
            counts[k] = (counts[k] ?: 0.0) + 1.0
        }
        if (counts.size > MAX_ENTRIES) {
            counts.entries.sortedBy { it.value }.take(counts.size - MAX_ENTRIES).forEach { counts.remove(it.key) }
        }
    }

    fun serialize(): String = counts.entries.joinToString(";") { "${it.key}=${"%.3f".format(java.util.Locale.ROOT, it.value)}" }

    companion object {
        private const val DECAY = 0.85
        private const val MAX_ENTRIES = 250

        fun parse(s: String?): MotionHistory {
            val map = HashMap<String, Double>()
            s?.split(';')?.forEach { part ->
                val kv = part.split('=')
                val v = kv.getOrNull(1)?.toDoubleOrNull()
                if (kv.size == 2 && v != null && kv[0].count { it == ',' } == 2) map[kv[0]] = v
            }
            return MotionHistory(map)
        }
    }
}
