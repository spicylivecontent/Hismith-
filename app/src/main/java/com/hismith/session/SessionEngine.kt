package com.hismith.session

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Human-like motion engine: speed + optional electronic stroke length.
 */
class SessionEngine(
    private val onSpeed: (Int) -> Unit,
    private val onStroke: (startPercent: Int, stopPercent: Int) -> Unit,
    private val onPhase: (String) -> Unit,
    private val onSpray: (seconds: Double) -> Unit,
    private val onFinished: () -> Unit
) {
    enum class Phase { IDLE, RHYTHM, BURST, HOLD, TEASE, FINAL }

    var isRunning = false
        private set

    private var sessionStartMs = 0L
    private var durationMs = 0L
    private var phase = Phase.IDLE
    private var phaseEndMs = 0L
    private var currentSpeed = 0
    private var targetSpeed = 0
    private var rampStartSpeed = 0
    private var rampStartMs = 0L
    private var rampDurationMs = 1500L
    private var finalTriggered = false
    private var lastLubeMs = 0L
    private var autoLube = true
    private var smartStroke = false

    // Stroke range (0–100). start = shallow, stop = deep end
    private var strokeStart = 15
    private var strokeStop = 85
    private var targetStrokeStart = 15
    private var targetStrokeStop = 85
    private var lastStrokeApplyMs = 0L

    private var teaseStep = 0
    private var teaseStepEndMs = 0L

    fun start(minMinutes: Int, maxMinutes: Int, autoLubeEnabled: Boolean, smartStrokeEnabled: Boolean) {
        autoLube = autoLubeEnabled
        smartStroke = smartStrokeEnabled
        durationMs = if (maxMinutes <= 0) 0L else {
            val minMs = minMinutes.coerceAtLeast(1) * 60_000L
            val maxMs = maxOf(minMinutes, maxMinutes) * 60_000L
            Random.nextLong(minMs, maxMs + 1)
        }
        sessionStartMs = System.currentTimeMillis()
        finalTriggered = false
        lastLubeMs = sessionStartMs
        lastStrokeApplyMs = sessionStartMs
        isRunning = true
        // Comfortable default stroke
        setStrokeTargets(12, 80)
        applyStrokeNow()
        startPhase(Phase.RHYTHM)
    }

    fun stop() {
        isRunning = false
        phase = Phase.IDLE
        rampTo(0, 600)
        onSpeed(0)
        onPhase("Stoppad")
    }

    fun emergencyStop() {
        isRunning = false
        phase = Phase.IDLE
        currentSpeed = 0
        targetSpeed = 0
        onSpeed(0)
        onPhase("NÖDSTOPP")
    }

    fun tick() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        val elapsed = now - sessionStartMs

        // Speed ramp
        if (currentSpeed != targetSpeed) {
            val t = ((now - rampStartMs).toFloat() / rampDurationMs).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            currentSpeed = (rampStartSpeed + (targetSpeed - rampStartSpeed) * eased).roundToInt()
            onSpeed(currentSpeed)
        }

        // Soft stroke ramp toward targets (human-like, not instant)
        if (smartStroke && now - lastStrokeApplyMs > 400) {
            strokeStart += ((targetStrokeStart - strokeStart) * 0.25f).roundToInt().coerceIn(-8, 8)
            strokeStop += ((targetStrokeStop - strokeStop) * 0.25f).roundToInt().coerceIn(-8, 8)
            if (strokeStart > strokeStop - 15) strokeStart = (strokeStop - 15).coerceAtLeast(0)
            applyStrokeNow()
            lastStrokeApplyMs = now
        }

        if (durationMs > 0) {
            val remaining = durationMs - elapsed
            if (remaining <= 20_000 && !finalTriggered) {
                finalTriggered = true
                startFinal()
            }
            if (remaining <= 0) {
                isRunning = false
                onSpeed(0)
                onPhase("Session klar")
                onFinished()
                return
            }
        }

        if (autoLube && now - lastLubeMs > Random.nextLong(18_000, 42_000)) {
            onSpray(0.4)
            lastLubeMs = now
        }

        when (phase) {
            Phase.TEASE -> tickTease(now)
            Phase.FINAL -> {}
            else -> if (now >= phaseEndMs) startPhase(pickNextPhase())
        }
    }

    private fun pickNextPhase(): Phase {
        val r = Random.nextFloat()
        return when {
            r < 0.62f -> Phase.RHYTHM
            r < 0.82f -> Phase.BURST
            r < 0.95f -> Phase.HOLD
            else -> Phase.TEASE
        }
    }

    private fun startPhase(p: Phase) {
        phase = p
        val now = System.currentTimeMillis()
        when (p) {
            Phase.RHYTHM -> {
                val base = Random.nextInt(26, 54)
                phaseEndMs = now + Random.nextLong(18_000, 55_000)
                onPhase("Rytm")
                rampTo(base, Random.nextLong(1400, 2800))
                // Medium variable stroke – feels natural
                setStrokeTargets(Random.nextInt(8, 22), Random.nextInt(70, 92))
            }
            Phase.BURST -> {
                phaseEndMs = now + Random.nextLong(9_000, 26_000)
                onPhase("Max Burst")
                rampTo(100, Random.nextLong(700, 1400))
                // Shorter stroke at max speed (safer / more intense focus)
                setStrokeTargets(Random.nextInt(15, 30), Random.nextInt(55, 75))
            }
            Phase.HOLD -> {
                phaseEndMs = now + Random.nextLong(8_000, 22_000)
                onPhase("Deep Hold")
                rampTo(0, Random.nextLong(900, 1800))
                // Deep parked position
                setStrokeTargets(Random.nextInt(55, 75), Random.nextInt(85, 100))
            }
            Phase.TEASE -> {
                phaseEndMs = now + Random.nextLong(14_000, 28_000)
                onPhase("Tease")
                teaseStep = 0
                teaseStepEndMs = now
                advanceTease(now)
            }
            else -> {}
        }
    }

    private fun tickTease(now: Long) {
        if (now >= teaseStepEndMs) advanceTease(now)
        if (now >= phaseEndMs) startPhase(pickNextPhase())
    }

    private fun advanceTease(now: Long) {
        when (teaseStep) {
            0 -> {
                rampTo(Random.nextInt(20, 33), 1600)
                setStrokeTargets(Random.nextInt(5, 15), Random.nextInt(40, 60)) // shallow tease
                teaseStepEndMs = now + Random.nextLong(3000, 5500)
                teaseStep = 1
            }
            1 -> {
                rampTo(Random.nextInt(0, 9), 1200)
                setStrokeTargets(Random.nextInt(60, 80), Random.nextInt(85, 98)) // deep still
                teaseStepEndMs = now + Random.nextLong(2500, 5000)
                teaseStep = 2
            }
            2 -> {
                rampTo(Random.nextInt(55, 79), 600)
                setStrokeTargets(Random.nextInt(10, 25), Random.nextInt(75, 95)) // full long strokes
                teaseStepEndMs = now + Random.nextLong(2000, 4000)
                teaseStep = 3
            }
            3 -> {
                rampTo(Random.nextInt(25, 42), 1800)
                setStrokeTargets(Random.nextInt(12, 28), Random.nextInt(65, 88))
                teaseStepEndMs = now + 999_999
                teaseStep = 4
            }
        }
    }

    private fun startFinal() {
        phase = Phase.FINAL
        onPhase("Final – klimax")
        onSpray(Random.nextDouble(4.5, 6.8))
        rampTo(15, 1200)
        // Long deep slow strokes for climax feel
        setStrokeTargets(Random.nextInt(20, 35), Random.nextInt(88, 100))
        applyStrokeNow()
    }

    private fun setStrokeTargets(start: Int, stop: Int) {
        targetStrokeStart = start.coerceIn(0, 85)
        targetStrokeStop = stop.coerceIn(targetStrokeStart + 15, 100)
    }

    private fun applyStrokeNow() {
        if (!smartStroke) return
        onStroke(strokeStart.coerceIn(0, 100), strokeStop.coerceIn(0, 100))
    }

    private fun rampTo(target: Int, durationMs: Long) {
        rampStartSpeed = currentSpeed
        targetSpeed = target.coerceIn(0, 100)
        rampStartMs = System.currentTimeMillis()
        rampDurationMs = durationMs.coerceAtLeast(200)
    }

    fun elapsedSeconds(): Long {
        if (!isRunning) return 0
        return (System.currentTimeMillis() - sessionStartMs) / 1000
    }

    fun progressPercent(): Float {
        if (durationMs <= 0) return 0f
        val elapsed = System.currentTimeMillis() - sessionStartMs
        return (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}
