package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowModelTest {

    private val model = FlowModel(listOf(CalPoint(20, 1.0), CalPoint(50, 3.0), CalPoint(100, 6.0)), calibrated = true)

    @Test
    fun interpolatesThroughOriginAndPoints() {
        assertEquals(0.0, model.flowAt(0), 1e-9)
        assertEquals(0.5, model.flowAt(10), 1e-9)
        assertEquals(1.0, model.flowAt(20), 1e-9)
        assertEquals(2.0, model.flowAt(35), 1e-9)
        assertEquals(6.0, model.flowAt(100), 1e-9)
    }

    @Test
    fun speedForFlowPicksLowestSpeedThatReachesIt() {
        assertEquals(35, model.speedForFlow(2.0, 5, 100))
        // Wanted flow above what the limit allows -> the limit.
        assertEquals(60, model.speedForFlow(10.0, 5, 60))
    }

    @Test
    fun planUsesFlowAndRampCompensation() {
        val plan = model.plan(ml = 6.0, wantedMlPerSec = 3.0, maxSpeed = 80)
        assertNotNull(plan)
        plan!!
        assertEquals(50, plan.speedPct)
        assertEquals(1, plan.segmentsMs.size)
        // 6 ml / 3 ml/s = 2000 ms, + ramp compensation 0.5 * 75 ms.
        assertEquals(2038L, plan.segmentsMs[0])
    }

    @Test
    fun longDosesSpeedUpBeforeSplitting() {
        // 100 ml at 1 ml/s would be 100 s; allowed to speed up to 80 % (4.8 ml/s) -> one segment.
        val plan = model.plan(ml = 100.0, wantedMlPerSec = 1.0, maxSpeed = 80)!!
        assertEquals(1, plan.segmentsMs.size)
        assertTrue(plan.segmentsMs[0] <= Limits.MAX_SEGMENT_MS + 100)
    }

    @Test
    fun veryLongDosesAreSplitBelowFirmwareLock() {
        val slow = FlowModel(listOf(CalPoint(100, 0.5)), calibrated = true)
        val plan = slow.plan(ml = 100.0, wantedMlPerSec = 0.5, maxSpeed = 100)!!
        assertEquals(3, plan.segmentsMs.size) // 200 s -> 3 x ~67 s
        assertTrue(plan.segmentsMs.all { it <= Limits.MAX_SEGMENT_MS + 100 })
        assertEquals(200_000.0, plan.runMs.toDouble(), 500.0)
    }

    @Test
    fun storageRoundTrip() {
        val restored = FlowModel.fromStorageString(model.toStorageString())
        assertTrue(restored.calibrated)
        assertEquals(model.points, restored.points)
        assertFalse(FlowModel.fromStorageString("").calibrated)
        assertFalse(FlowModel.fromStorageString("garbage").calibrated)
    }

    @Test
    fun duplicateSpeedsAreAveraged() {
        val m = FlowModel(listOf(CalPoint(50, 2.0), CalPoint(50, 4.0)), calibrated = true)
        assertEquals(1, m.points.size)
        assertEquals(3.0, m.flowAt(50), 1e-9)
    }

    @Test
    fun pulsedPlanShrinksSpurtsAndGrowsPauses() {
        val plan = model.planPulsed(ml = 30.0, wantedMlPerSec = 6.0, maxSpeed = 100, pulses = 6)!!
        assertEquals(6, plan.segmentsMs.size)
        assertEquals(5, plan.pausesMs.size)
        assertTrue(plan.segmentsMs.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(plan.pausesMs.zipWithNext().all { (a, b) -> b > a })
        assertTrue(plan.pulsed)
        // Delivered volume (run time minus ramp compensation) matches the request.
        val ml = plan.segmentsMs.sumOf { (it - FlowModel.rampCompensationMs(plan.speedPct)) / 1000.0 * plan.mlPerSec }
        assertEquals(30.0, ml, 0.2)
    }
}
