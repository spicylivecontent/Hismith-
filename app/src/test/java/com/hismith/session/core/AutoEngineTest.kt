package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoEngineTest {

    private fun config(
        mode: SessionMode = SessionMode.ANAL,
        min: Int = 10,
        max: Int = 12,
        intensity: Int = 50,
        nonstop: Boolean = false,
        autoLube: Boolean = true,
        style: SessionStyle = SessionStyle.BALANCED,
        surprise: SurpriseLevel = SurpriseLevel.OFF,
        microOnBurst: Boolean = true,
    ) = SessionConfig(
        mode = mode, minMinutes = min, maxMinutes = max, intensity = intensity,
        autoLube = autoLube, finalEnabled = true, finalMinMl = 15.0, finalMaxMl = 50.0,
        microDose = MicroDoseSettings(1.0, 20, 45), smartStroke = true, nonstop = nonstop,
        style = style, surprise = surprise, surpriseMl = 15.0, microOnBurst = microOnBurst,
    )

    /** Runs a whole session on a simulated clock and returns all frames. */
    private fun simulate(engine: AutoEngine, step: Long = 80): List<Pair<Long, EngineFrame>> {
        val frames = ArrayList<Pair<Long, EngineFrame>>()
        engine.start(1_000)
        var t = 1_000L
        while (t < 1_000 + 4 * 3_600_000L) {
            val f = engine.tick(t)
            frames += t to f
            if (f.finished) break
            t += step
        }
        return frames
    }

    private val doseMs: (Double) -> Long = { ml -> (ml / 4.0 * 1000).toLong() }

    @Test
    fun sameSeedGivesSameSession() {
        val a = simulate(AutoEngine(config(), 42, MotionHistory(), doseMs)).map { it.second.speed to it.second.lube }
        val b = simulate(AutoEngine(config(), 42, MotionHistory(), doseMs)).map { it.second.speed to it.second.lube }
        assertEquals(a, b)
        val c = simulate(AutoEngine(config(), 43, MotionHistory(), doseMs)).map { it.second.speed to it.second.lube }
        assertNotEquals(a, c)
    }

    @Test
    fun durationWithinMinMaxAndEndsAtZero() {
        repeat(20) { seed ->
            val e = AutoEngine(config(min = 10, max = 12), seed.toLong(), MotionHistory(), doseMs)
            val frames = simulate(e)
            assertTrue(e.durationMs in 600_000L..720_000L)
            assertTrue(frames.last().second.finished)
            assertEquals(0, frames.last().second.speed)
            assertTrue(frames.all { it.second.speed in 0..100 })
        }
    }

    @Test
    fun finalDoseIsTimedToFinishBeforeTheEnd() {
        repeat(20) { seed ->
            val e = AutoEngine(config(), seed.toLong(), MotionHistory(), doseMs)
            val frames = simulate(e)
            val finals = frames.filter { it.second.lube?.kind == DoseKind.FINAL }
            assertEquals(1, finals.size)
            val (at, f) = finals.first()
            val ml = f.lube!!.ml
            assertTrue(ml in 15.0..50.0)
            val end = frames.last().first
            assertTrue("final dose must end before the session ends", at + doseMs(ml) < end)
            // No micro doses once the final has started.
            assertTrue(frames.none { it.first > at && it.second.lube?.kind == DoseKind.MICRO })
        }
    }

    @Test
    fun microDosesRespectInterval() {
        val frames = simulate(AutoEngine(config(microOnBurst = false), 7, MotionHistory(), doseMs))
        val times = frames.filter { it.second.lube?.kind == DoseKind.MICRO }.map { it.first }
        assertTrue(times.size >= 5)
        times.zipWithNext().forEach { (a, b) -> assertTrue(b - a in 20_000L..45_200L) }
    }

    @Test
    fun pocketNeverAsksForLube() {
        repeat(5) { seed ->
            val frames = simulate(AutoEngine(config(mode = SessionMode.POCKET), seed.toLong(), MotionHistory(), doseMs))
            assertTrue(frames.none { it.second.lube != null })
        }
    }

    @Test
    fun nonstopKeepsMovingUntilTheFinal() {
        repeat(10) { seed ->
            val frames = simulate(AutoEngine(config(nonstop = true), seed.toLong(), MotionHistory(), doseMs))
            val beforeFinal = frames.takeWhile { it.second.behavior != AutoEngine.Behavior.FINAL }
            // Allow the very first ramp from 0.
            assertTrue(beforeFinal.drop(150).all { it.second.speed >= 12 })
            assertTrue(frames.none { it.second.behavior == AutoEngine.Behavior.HOLD })
        }
    }

    @Test
    fun noJumpsBetweenTicks() {
        repeat(10) { seed ->
            val frames = simulate(AutoEngine(config(intensity = 100), seed.toLong(), MotionHistory(), doseMs))
            frames.zipWithNext().forEach { (a, b) ->
                // 80 ms tick: the fastest ramp (150 ms floor) plus noise stays well below a full jump.
                assertTrue("jump ${a.second.speed}->${b.second.speed}", abs(a.second.speed - b.second.speed) <= 70)
            }
        }
    }

    @Test
    fun pauseOutputsZeroAndResumeRampsFromZero() {
        val e = AutoEngine(config(), 3, MotionHistory(), doseMs)
        e.start(0)
        var t = 0L
        while (t < 120_000) { e.tick(t); t += 80 }
        e.pause(t)
        assertEquals(0, e.tick(t + 1_000).speed)
        assertEquals(0, e.tick(t + 60_000).speed)
        val elapsedBefore = e.elapsedMs(t)
        e.resume(t + 60_000)
        assertEquals(elapsedBefore, e.elapsedMs(t + 60_000))
        val first = e.tick(t + 60_080).speed
        assertTrue("resume must start low, was $first", first <= 10)
    }

    @Test
    fun sessionsDoNotRepeat() {
        val history = MotionHistory()
        val signatures = HashSet<String>()
        var immediateRepeats = 0
        var transitions = 0
        repeat(150) { seed ->
            val e = AutoEngine(config(min = 20, max = 30), 1_000L + seed, history, doseMs)
            val frames = simulate(e, step = 200)
            val sig = e.sequence.joinToString("") { it.name.take(2) } + "|" +
                frames.filterIndexed { i, _ -> i % 25 == 0 }.joinToString(",") { (it.second.speed / 10).toString() }
            signatures += sig
            e.sequence.zipWithNext().forEach { (a, b) -> transitions++; if (a == b) immediateRepeats++ }
        }
        assertEquals("every session must be unique", 150, signatures.size)
        assertTrue("immediate repeats $immediateRepeats/$transitions", immediateRepeats.toDouble() / transitions < 0.08)
    }

    @Test
    fun historyPenalisesCommonSequences() {
        val h = MotionHistory()
        val seq = List(30) { if (it % 2 == 0) AutoEngine.Behavior.RHYTHM else AutoEngine.Behavior.BURST }
        repeat(5) { h.recordSession(seq) }
        assertTrue(h.count(AutoEngine.Behavior.RHYTHM, AutoEngine.Behavior.BURST, AutoEngine.Behavior.RHYTHM) > 1.0)
        val restored = MotionHistory.parse(h.serialize())
        assertEquals(
            h.count(AutoEngine.Behavior.RHYTHM, AutoEngine.Behavior.BURST, AutoEngine.Behavior.RHYTHM),
            restored.count(AutoEngine.Behavior.RHYTHM, AutoEngine.Behavior.BURST, AutoEngine.Behavior.RHYTHM),
            0.01,
        )
    }

    @Test
    fun finishSoonStartsTheFinalNow() {
        val e = AutoEngine(config(min = 60, max = 60), 9, MotionHistory(), doseMs)
        e.start(0)
        var t = 0L
        while (t < 60_000) { e.tick(t); t += 80 }
        e.finishSoon(t)
        var sawFinal = false
        var done = false
        while (t < 400_000 && !done) {
            val f = e.tick(t)
            if (f.behavior == AutoEngine.Behavior.FINAL) sawFinal = true
            done = f.finished
            t += 80
        }
        assertTrue(sawFinal)
        assertTrue(done)
        assertTrue(t < 250_000)
    }

    @Test
    fun unlimitedSessionHasNoFinalUntilAsked() {
        val e = AutoEngine(config(min = 10, max = 0), 5, MotionHistory(), doseMs)
        e.start(0)
        var t = 0L
        while (t < 3_600_000) {
            val f = e.tick(t)
            assertTrue(!f.finished)
            assertTrue(f.lube?.kind != DoseKind.FINAL)
            t += 500
        }
    }

    @Test
    fun microDoseFollowsBurstsButNeverTooOften() {
        repeat(10) { seed ->
            val frames = simulate(AutoEngine(config(min = 20, max = 25, intensity = 80), seed.toLong(), MotionHistory(), doseMs))
            val micros = frames.filter { it.second.lube?.kind == DoseKind.MICRO }
            micros.map { it.first }.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 10_000) }
            // At least one micro dose lands right as a burst starts.
            val burstStarts = frames.zipWithNext()
                .filter { (a, b) -> a.second.behavior != AutoEngine.Behavior.BURST && b.second.behavior == AutoEngine.Behavior.BURST }
                .map { it.second.first }
            if (burstStarts.isNotEmpty()) {
                assertTrue(micros.any { m -> burstStarts.any { m.first - it in 0..500 } })
            }
        }
    }

    private fun behaviourShare(style: SessionStyle, b: AutoEngine.Behavior): Double {
        var hits = 0
        var total = 0
        repeat(40) { seed ->
            val e = AutoEngine(config(min = 30, max = 30, style = style), 500L + seed, MotionHistory(), doseMs)
            simulate(e, step = 250)
            val picks = e.sequence.filter { it in AutoEngine.Behavior.PICKABLE }
            hits += picks.count { it == b }
            total += picks.size
        }
        return hits.toDouble() / total
    }

    @Test
    fun stylesChangeTheCharacter() {
        val edgeBalanced = behaviourShare(SessionStyle.BALANCED, AutoEngine.Behavior.EDGE)
        val edgeEdging = behaviourShare(SessionStyle.EDGING, AutoEngine.Behavior.EDGE)
        assertTrue("edging $edgeEdging vs $edgeBalanced", edgeEdging > edgeBalanced * 1.8)
        val burstBalanced = behaviourShare(SessionStyle.BALANCED, AutoEngine.Behavior.BURST)
        assertTrue(behaviourShare(SessionStyle.SENSUAL, AutoEngine.Behavior.BURST) < burstBalanced * 0.6)
        assertTrue(behaviourShare(SessionStyle.INTENSE, AutoEngine.Behavior.BURST) > burstBalanced * 1.5)
        assertTrue(behaviourShare(SessionStyle.TEASE, AutoEngine.Behavior.TEASE) > behaviourShare(SessionStyle.BALANCED, AutoEngine.Behavior.TEASE) * 1.8)
    }

    @Test
    fun quickActionSwitchesBehaviourAndRamps() {
        val e = AutoEngine(config(), 11, MotionHistory(), doseMs)
        e.start(0)
        var t = 0L
        while (t < 60_000) { e.tick(t); t += 80 }
        assertTrue(e.trigger(QuickAction.BOOST, t))
        val start = e.tick(t)
        assertEquals(AutoEngine.Behavior.BURST, start.behavior)
        var peak = 0
        repeat(40) { t += 80; peak = maxOf(peak, e.tick(t).speed) }
        assertTrue("boost peak $peak", peak >= 65)
        e.trigger(QuickAction.REST, t)
        assertEquals(AutoEngine.Behavior.HOLD, e.tick(t + 80).behavior)
        // Not during a pause.
        e.pause(t + 100)
        assertTrue(!e.trigger(QuickAction.EDGE, t + 200))
    }

    @Test
    fun quickActionsAreIgnoredDuringTheFinal() {
        val e = AutoEngine(config(min = 5, max = 5), 4, MotionHistory(), doseMs)
        e.start(0)
        var t = 0L
        while (!e.finalStartedFlag && t < 400_000) { e.tick(t); t += 80 }
        assertTrue(e.finalStartedFlag)
        assertTrue(!e.trigger(QuickAction.BOOST, t))
    }

    @Test
    fun surprisesBurstAndSquirt() {
        val frames = simulate(AutoEngine(config(min = 40, max = 40, surprise = SurpriseLevel.OFTEN), 21, MotionHistory(), doseMs), step = 100)
        val extras = frames.filter { it.second.lube?.kind == DoseKind.EXTRA }
        assertTrue("surprises ${extras.size}", extras.size >= 6)
        extras.forEach { (at, f) ->
            assertEquals(15.0, f.lube!!.ml, 1e-9)
            assertEquals(AutoEngine.Behavior.BURST, f.behavior)
            assertTrue(frames.first { it.first == at }.second.label == "Överraskning!")
        }
        extras.map { it.first }.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 89_000) }
    }

    @Test
    fun pocketSurprisesHaveNoLube() {
        val frames = simulate(AutoEngine(config(mode = SessionMode.POCKET, min = 30, max = 30, surprise = SurpriseLevel.OFTEN), 3, MotionHistory(), doseMs))
        assertTrue(frames.none { it.second.lube != null })
        assertTrue(frames.any { it.second.label == "Överraskning!" })
    }

    @Test
    fun styleCanChangeLive() {
        val e = AutoEngine(config(), 8, MotionHistory(), doseMs)
        e.start(0)
        val before = e.weights().getValue(AutoEngine.Behavior.EDGE)
        e.setStyle(SessionStyle.EDGING)
        assertEquals(SessionStyle.EDGING, e.currentStyle)
        assertEquals(before * SessionStyle.EDGING.edge, e.weights().getValue(AutoEngine.Behavior.EDGE), 1e-9)
    }
}
