package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandArbiterTest {

    private val motion = FakeMotion()
    private val pump = FakeLubeDriver()
    private val doses = DoseController({ LubeSettings() }, { FlowModel.DEFAULT }).also { it.driver = pump }
    private val arbiter = CommandArbiter(motion, doses, MotionLimits(maxSpeedAnal = 80, remoteMaxSpeed = 50, slewPerSecond = 100))

    private fun frame(speed: Int) = EngineFrame(speed, 10, 90, "x", AutoEngine.Behavior.RHYTHM, null, false)

    @Test
    fun autoOutputIsScaledIntoTheModeLimit() {
        arbiter.setMode(SessionMode.ANAL)
        arbiter.beginAuto(Source.LOCAL)
        arbiter.applyAuto(frame(100), smartStroke = false)
        assertEquals(80, motion.last)
        arbiter.applyAuto(frame(50), smartStroke = false)
        assertEquals(40, motion.last)
    }

    @Test
    fun stopBlocksMotionUntilExplicitStart() {
        arbiter.beginManual(Source.LOCAL)
        arbiter.manualSpeed(Source.LOCAL, 60)
        // Ticks longer than 500 ms are capped, so a stalled loop never causes a jump.
        arbiter.tick(0); arbiter.tick(1_000)
        assertEquals(50, motion.last)
        arbiter.tick(1_500)
        assertEquals(60, motion.last)
        arbiter.stop(Source.LOCAL, "STOPP")
        assertEquals(0, motion.last)
        assertEquals(CommandArbiter.Safety.STOPPED, arbiter.safety)
        assertFalse(arbiter.manualSpeed(Source.LOCAL, 60))
        arbiter.applyAuto(frame(90), false)
        assertEquals(0, motion.last)
        // STOP is idempotent.
        arbiter.stop(Source.LOCAL, "STOPP")
        assertEquals(CommandArbiter.Safety.STOPPED, arbiter.safety)
        assertTrue(arbiter.beginManual(Source.LOCAL))
        assertTrue(arbiter.manualSpeed(Source.LOCAL, 30))
    }

    @Test
    fun manualSpeedIsSlewed() {
        arbiter.beginManual(Source.LOCAL)
        arbiter.manualSpeed(Source.LOCAL, 80)
        arbiter.tick(0)
        arbiter.tick(100)
        assertEquals(10, motion.last) // 100 %/s * 0.1 s
        arbiter.tick(200)
        assertEquals(20, motion.last)
    }

    @Test
    fun remoteIsClampedAndCannotTakeOverAuto() {
        arbiter.beginAuto(Source.LOCAL)
        assertFalse(arbiter.beginManual(Source.REMOTE))
        arbiter.endControl(Source.LOCAL)
        assertTrue(arbiter.beginManual(Source.REMOTE))
        assertFalse(arbiter.manualSpeed(Source.LOCAL, 10)) // not the owner
        assertTrue(arbiter.manualSpeed(Source.REMOTE, 100))
        assertEquals(50, arbiter.manualTargetPercent)
    }

    @Test
    fun pocketRefusesLubeFromEverySource() {
        arbiter.setMode(SessionMode.POCKET)
        for (src in Source.values()) {
            val r = arbiter.requestDose(src, DoseKind.EXTRA, 10.0, 0)
            assertTrue(r is DoseResult.Rejected)
        }
        assertTrue(pump.commands.none { it.startsWith("RUN") })
    }

    @Test
    fun remoteSpraysPlainDosesWithinTheCapPerClick() {
        arbiter.setMode(SessionMode.ANAL)
        arbiter.limits = arbiter.limits.copy(remoteMaxMl = 20)
        val r = arbiter.requestDose(Source.REMOTE, DoseKind.MANUAL, 30.0, 0, pumpPct = 50)
        assertEquals(20.0, (r as DoseResult.Started).plan.ml, 0.001)
        // Only plain doses: no SUPER, final or service from the link.
        for (kind in listOf(DoseKind.EXTRA, DoseKind.SUPER, DoseKind.FINAL, DoseKind.SERVICE, DoseKind.MICRO)) {
            assertTrue(arbiter.requestDose(Source.REMOTE, kind, 5.0, 0) is DoseResult.Rejected)
        }
    }

    @Test
    fun remoteLubeCanBeTurnedOff() {
        arbiter.setMode(SessionMode.ANAL)
        arbiter.limits = arbiter.limits.copy(remoteAllowLube = false)
        assertTrue(arbiter.requestDose(Source.REMOTE, DoseKind.MANUAL, 5.0, 0, pumpPct = 50) is DoseResult.Rejected)
        assertTrue(arbiter.requestDose(Source.LOCAL, DoseKind.MANUAL, 5.0, 0) is DoseResult.Started)
    }

    @Test
    fun remoteIsAManualModeUnlessAutoIsAllowed() {
        assertFalse(arbiter.beginAuto(Source.REMOTE))
        assertEquals(CommandArbiter.Control.NONE, arbiter.control)
        assertTrue(arbiter.beginManual(Source.REMOTE))
        arbiter.limits = arbiter.limits.copy(remoteAllowAuto = true)
        assertTrue(arbiter.beginAuto(Source.REMOTE))
    }

    @Test
    fun pauseStopsMotionAndDoseAndResumeStartsFromZero() {
        arbiter.setMode(SessionMode.ANAL)
        arbiter.beginManual(Source.LOCAL)
        arbiter.manualSpeed(Source.LOCAL, 50)
        arbiter.tick(0); arbiter.tick(1_000)
        arbiter.requestDose(Source.LOCAL, DoseKind.EXTRA, 10.0, 1_000)
        arbiter.pause("Maskin tappade kontakten")
        assertEquals(0, motion.last)
        assertFalse(doses.isBusy)
        assertTrue(arbiter.resume(Source.LOCAL))
        arbiter.tick(1_100)
        assertEquals(0, motion.last) // no automatic restart of the old speed
    }

    @Test
    fun stopAlsoStopsThePump() {
        arbiter.setMode(SessionMode.ANAL)
        arbiter.requestDose(Source.LOCAL, DoseKind.SUPER, 50.0, 0)
        assertTrue(doses.isBusy)
        arbiter.stop(Source.REMOTE, "STOPP")
        assertFalse(doses.isBusy)
        assertEquals("STOP", pump.commands.last())
    }

    @Test
    fun serviceOnlyWhenIdle() {
        arbiter.beginManual(Source.LOCAL)
        assertTrue(arbiter.requestService(40, 3_000, 0) is DoseResult.Rejected)
        arbiter.endControl(Source.LOCAL)
        assertTrue(arbiter.requestService(40, 3_000, 0) is DoseResult.Started)
    }
}
