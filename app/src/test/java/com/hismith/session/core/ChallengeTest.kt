package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {

    private fun challenge(
        progression: Progression = Progression.LINEAR,
        policy: MissedPolicy = MissedPolicy.MARK_MISSED,
    ) = Challenge(
        name = "30 dagar",
        days = 30,
        startEpochDay = 1_000,
        progression = progression,
        start = DayTarget(10, 30, 15),
        end = DayTarget(40, 90, 50),
        lastDayNonstop = true,
        missedPolicy = policy,
        mode = SessionMode.ANAL,
    )

    @Test
    fun progressionHitsStartAndEnd() {
        for (p in Progression.values()) {
            val c = challenge(progression = p)
            assertEquals(DayTarget(10, 30, 15), c.target(1))
            assertEquals(DayTarget(40, 90, 50), c.target(30))
        }
        val linear = challenge(Progression.LINEAR).target(15).minutes
        assertTrue(challenge(Progression.SOFT_START).target(15).minutes < linear)
        assertTrue(challenge(Progression.SOFT_END).target(15).minutes > linear)
    }

    @Test
    fun lastDayIsNonstopGoalOnly() {
        val c = challenge()
        val cfg = c.sessionConfig(30, LubeSettings(), smartStroke = false, autoLube = true)
        assertTrue(cfg.nonstop)
        assertEquals(30, cfg.challengeDay)
        assertFalse(c.sessionConfig(29, LubeSettings(), false, true).nonstop)
        assertEquals(40, cfg.minMinutes)
        assertTrue(cfg.maxMinutes > cfg.minMinutes)
    }

    @Test
    fun manualOverridesWin() {
        val c = challenge(Progression.MANUAL).withManualTarget(5, DayTarget(99, 10, 0))
        assertEquals(99, c.target(5).minutes)
        assertFalse(c.sessionConfig(5, LubeSettings(), false, true).finalEnabled)
    }

    @Test
    fun markMissedPolicy() {
        val c = challenge(policy = MissedPolicy.MARK_MISSED).record(1, DayStatus.DONE, 100, 1_000)
        val rolled = c.rollover(today = 1_003) // days 2 and 3 passed
        assertEquals(DayStatus.MISSED, rolled.status(2))
        assertEquals(DayStatus.MISSED, rolled.status(3))
        assertEquals(DayStatus.PENDING, rolled.status(4))
        assertEquals(4, rolled.currentDay(1_003))
    }

    @Test
    fun shiftPolicyMovesTheRest() {
        val c = challenge(policy = MissedPolicy.SHIFT).record(1, DayStatus.DONE, 100, 1_000)
        val rolled = c.rollover(today = 1_003)
        assertEquals(2, rolled.shiftDays)
        assertEquals(2, rolled.currentDay(1_003))
        assertEquals(1_003L, rolled.dateOf(2))
    }

    @Test
    fun catchUpPolicyKeepsCalendar() {
        val c = challenge(policy = MissedPolicy.CATCH_UP).record(1, DayStatus.DONE, 100, 1_000)
        val rolled = c.rollover(today = 1_003)
        assertEquals(0, rolled.shiftDays)
        assertEquals(DayStatus.PENDING, rolled.status(2))
        assertEquals(2, rolled.currentDay(1_003))
    }

    @Test
    fun pauseShiftsScheduleOnResume() {
        val c = challenge().pause(1_005)
        assertNull(c.currentDay(1_006))
        val resumed = c.resume(1_010)
        assertEquals(5, resumed.shiftDays)
        assertEquals(6, resumed.currentDay(1_010))
    }

    @Test
    fun partialNeverOverwritesDone() {
        val c = challenge().record(3, DayStatus.DONE, 100, 1_002).record(3, DayStatus.PARTIAL, 40, 1_002)
        assertEquals(DayStatus.DONE, c.status(3))
    }

    @Test
    fun jsonRoundTrip() {
        val c = challenge(Progression.SOFT_END, MissedPolicy.SHIFT)
            .withManualTarget(7, DayTarget(12, 34, 20))
            .record(1, DayStatus.DONE, 100, 1_000)
            .record(2, DayStatus.PARTIAL, 55, 1_001)
            .pause(1_002)
        val restored = Challenge.fromJson(org.json.JSONObject(c.toJson().toString()))
        assertNotNull(restored)
        assertEquals(c, restored)
    }
}
