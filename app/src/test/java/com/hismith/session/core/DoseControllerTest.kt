package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseControllerTest {

    private val driver = FakeLubeDriver()
    private val events = ArrayList<String>()
    private var settings = LubeSettings(flowMicro = 1.0, flowNormal = 2.0, flowStrong = 4.0, maxPumpSpeed = 100, maxSessionMl = 100.0)
    private val model = FlowModel(listOf(CalPoint(100, 4.0)), calibrated = true) // 0.04 ml/s per %
    private val ctl = DoseController({ settings }, { model }, { events += it }).also { it.driver = driver }

    private fun runUntil(from: Long, to: Long, step: Long = 50): Long {
        var t = from
        while (t <= to) {
            ctl.tick(t)
            t += step
        }
        return t
    }

    @Test
    fun streamsRunWithLeaseAndEndsWithExactRemainder() {
        val r = ctl.submit(DoseRequest(DoseKind.MANUAL, 4.0, Source.LOCAL), 0)
        assertTrue(r is DoseResult.Started)
        val plan = (r as DoseResult.Started).plan
        assertEquals(50, plan.speedPct) // 2 ml/s wanted -> 50 %
        val runMs = plan.segmentsMs[0] // 2000 ms + compensation
        assertEquals("RUN:50:700", driver.commands.first())
        runUntil(50, runMs + 500)
        val runs = driver.commands.filter { it.startsWith("RUN") }
        // A command at least every 250 ms over the whole dose.
        assertTrue(runs.size >= (runMs / 250).toInt())
        // Last lease is shorter than the standard lease: the exact rest of the dose.
        val lastLease = runs.last().split(':')[2].toLong()
        assertTrue(lastLease < DoseController.LEASE_MS)
        assertTrue(!ctl.isBusy)
        assertEquals(4.0, ctl.sessionDeliveredMl, 0.15)
    }

    @Test
    fun microDoseNeverInterruptsOrStacks() {
        ctl.submit(DoseRequest(DoseKind.EXTRA, 10.0, Source.LOCAL), 0)
        val micro = ctl.submit(DoseRequest(DoseKind.MICRO, 0.5, Source.AUTO), 100)
        assertTrue(micro is DoseResult.Rejected)
        assertEquals(DoseKind.EXTRA, ctl.activeInfo(100)!!.kind)
        assertTrue(ctl.queuedKinds.isEmpty())
    }

    @Test
    fun higherPriorityPreemptsLowerQueues() {
        ctl.submit(DoseRequest(DoseKind.MANUAL, 5.0, Source.LOCAL), 0)
        val sup = ctl.submit(DoseRequest(DoseKind.SUPER, 20.0, Source.LOCAL), 100)
        assertTrue(sup is DoseResult.Preempted)
        assertEquals(DoseKind.SUPER, ctl.activeInfo(100)!!.kind)
        val extra = ctl.submit(DoseRequest(DoseKind.EXTRA, 5.0, Source.LOCAL), 200)
        assertTrue(extra is DoseResult.Queued)
        val manual = ctl.submit(DoseRequest(DoseKind.MANUAL, 2.0, Source.LOCAL), 250)
        assertTrue(manual is DoseResult.Queued)
        assertEquals(listOf(DoseKind.EXTRA, DoseKind.MANUAL), ctl.queuedKinds)
        // Run SUPER to completion: EXTRA starts next.
        runUntil(300, 6_000)
        assertEquals(DoseKind.EXTRA, ctl.activeInfo(6_000)?.kind)
    }

    @Test
    fun sessionTotalIsCapped() {
        settings = settings.copy(maxSessionMl = 30.0)
        val first = ctl.submit(DoseRequest(DoseKind.SUPER, 25.0, Source.LOCAL), 0)
        assertTrue(first is DoseResult.Started)
        runUntil(50, 20_000)
        val second = ctl.submit(DoseRequest(DoseKind.SUPER, 25.0, Source.LOCAL), 20_100)
        // Reduced to what is left (about 5 ml).
        assertTrue(second is DoseResult.Started)
        assertEquals(5.0, ctl.activeInfo(20_100)!!.ml, 0.3)
        runUntil(20_150, 30_000)
        val third = ctl.submit(DoseRequest(DoseKind.MICRO, 0.5, Source.AUTO), 30_100)
        assertTrue(third is DoseResult.Rejected)
    }

    @Test
    fun clampsToKindLimits() {
        val r = ctl.submit(DoseRequest(DoseKind.MANUAL, 500.0, Source.LOCAL), 0) as DoseResult.Started
        assertEquals(Limits.MANUAL_MAX_ML, r.plan.ml, 1e-9)
    }

    @Test
    fun stopAllClearsEverythingAndStopsPump() {
        ctl.submit(DoseRequest(DoseKind.SUPER, 20.0, Source.LOCAL), 0)
        ctl.submit(DoseRequest(DoseKind.EXTRA, 5.0, Source.LOCAL), 10)
        ctl.stopAll("test")
        assertTrue(!ctl.isBusy)
        assertTrue(ctl.queuedKinds.isEmpty())
        assertEquals("STOP", driver.commands.last())
    }

    @Test
    fun lostDriverAbortsWithoutRestart() {
        ctl.submit(DoseRequest(DoseKind.SUPER, 20.0, Source.LOCAL), 0)
        driver.isReady = false
        ctl.tick(100)
        assertTrue(!ctl.isBusy)
        driver.isReady = true
        val before = driver.commands.size
        runUntil(200, 2_000)
        assertEquals(before, driver.commands.size) // nothing resumes by itself
    }

    @Test
    fun serviceRunIsRefusedWhileDosing() {
        ctl.submit(DoseRequest(DoseKind.MANUAL, 5.0, Source.LOCAL), 0)
        val r = ctl.submit(DoseRequest(DoseKind.SERVICE, 0.0, Source.LOCAL, -50, 3_000), 10)
        assertTrue(r is DoseResult.Rejected)
    }

    @Test
    fun serviceRunReversesAndDoesNotCountTowardsSession() {
        val r = ctl.submit(DoseRequest(DoseKind.SERVICE, 0.0, Source.LOCAL, -80, 2_000), 0)
        assertTrue(r is DoseResult.Started)
        assertEquals("RUN:-60:700", driver.commands.first()) // reverse capped at 60 %
        runUntil(50, 3_000)
        assertEquals(0.0, ctl.sessionDeliveredMl, 1e-9)
    }

    @Test
    fun longDoseIsSegmentedWithPause() {
        val slow = FlowModel(listOf(CalPoint(100, 0.5)), calibrated = true)
        val c = DoseController({ settings.copy(maxSessionMl = 1000.0) }, { slow }).also { it.driver = driver }
        val r = c.submit(DoseRequest(DoseKind.SUPER, 60.0, Source.LOCAL), 0) as DoseResult.Started
        assertEquals(2, r.plan.segmentsMs.size)
        var t = 0L
        var sawPause = false
        while (c.isBusy && t < 200_000) {
            t += 50
            val before = driver.commands.size
            c.tick(t)
            val info = c.activeInfo(t)
            if (info != null && info.segment == 2 && before == driver.commands.size) sawPause = true
        }
        assertTrue(sawPause)
        assertTrue(!c.isBusy)
    }

    @Test
    fun launcherWithoutSpeedControlIsPlannedAtFullFlow() {
        val fixed = FakeLubeDriver(supportsSpeed = false)
        val c = DoseController({ settings }, { FlowModel(listOf(CalPoint(100, 2.0)), true) }).also { it.driver = fixed }
        val r = c.submit(DoseRequest(DoseKind.MICRO, 1.0, Source.AUTO), 0) as DoseResult.Started
        assertEquals(100, r.plan.speedPct)
        assertEquals(575L, r.plan.segmentsMs[0]) // 1 ml / 2 ml/s + 75 ms ramp compensation
    }
}
